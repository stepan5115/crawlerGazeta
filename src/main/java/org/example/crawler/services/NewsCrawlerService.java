package org.example.crawler.services;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.crawler.entities.*;
import org.example.crawler.repositories.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsCrawlerService {
    public final static String SUCCESS = "Success: ";
    public final static String FAILED = "Failed";

    private final NewsRepository newsRepository;
    private final AuthorRepository authorRepository;
    private final CategoryRepository categoryRepository;
    private final CrawlLogRepository crawlLogRepository;

    private final AtomicInteger counter_new_news = new AtomicInteger(0);
    private final AtomicBoolean ifErrorCluster = new AtomicBoolean(false);
    private final AtomicBoolean ifErrorCategory = new AtomicBoolean(false);
    private final AtomicBoolean ifErrorAuthor = new AtomicBoolean(false);
    private final AtomicBoolean ifErrorNews = new AtomicBoolean(false);

    @Value("${crawler.base-url}")
    private String baseUrl;

    @Value("${crawler.interval-outing-minutes}")
    private int expirationMinutes;
    @Value("${crawler.interval-minutes}")
    private int intervalCrawlMinutes;

    @Value("${crawler.max-attempts}")
    private int maxAttempts;

    @Value("${unknown_author}")
    private String unknownAuthorName;

    @Value("${unknown_email}")
    private String unknownEmail;

    @Value("${unknown_category}")
    private String unknownCategoryName;

    private final ConcurrentLinkedQueue<String> processedNewsRightNow = new ConcurrentLinkedQueue<>();

    private Category unknownCategory;
    private Author unknownAuthor;
    @PostConstruct
    private void init() {
        this.unknownAuthor = new Author(unknownAuthorName, unknownEmail);
        this.unknownCategory = new Category(unknownCategoryName);
    }

    //Аннотация @Scheduled из Spring используется для регулярного запуска метода по расписанию
    //fixedRateString - Запуск метода с фиксированным интервалом после начала предыдущего вызова
    //timeUnit - По умолчанию fixedRate и fixedDelay работают в миллисекундах, но меняем на минуты
    @Scheduled(fixedRateString = "${crawler.interval-func}", timeUnit = TimeUnit.MINUTES)
    public void crawlNews() {
        Optional<CrawlLog> lastLog = crawlLogRepository.findFirstByOrderByCrawlTimeDesc();
        if (lastLog.isPresent() && !lastLog.get().getErrorMessage().equals(FAILED) &&
                Duration.between(lastLog.get().getCrawlTime(), LocalDateTime.now()).toMinutes() <= intervalCrawlMinutes) {
            System.out.println("Last crawl not expired");
            return;
        }
        System.out.println("start crawling....");
        counter_new_news.set(0);
        ifErrorCluster.set(false);
        ifErrorCategory.set(false);
        ifErrorAuthor.set(false);
        ifErrorNews.set(false);
        List<String> categoriesLinks = new LinkedList<>();
        try {
            Document doc = fetchDocument(baseUrl);
            Element menuContent = doc.selectFirst("div.b_menu-content");
            if (menuContent == null) {
                log.error("No menu content find on base url: {}", baseUrl);
                CrawlLog crawlLog = new CrawlLog();
                crawlLog.setNewNewsCount(0);
                crawlLog.setErrorMessage(FAILED);
                return;
            }
            Elements menuItems = menuContent.select("div.b_menu-item a[href]");
            for (Element menuItem : menuItems) {
                String href = menuItem.attr("href");
                if (!href.equals("/subjects/civilization/") &&
                    !href.equals("/history.shtml") &&
                    !href.equals("/about/") &&
                    !href.equals("/quiz/") &&
                    !href.equals("/infographics/") &&
                    !href.equals("/photo/")
                ) {
                    categoriesLinks.add(baseUrl + href);
                }
            }
            List<Thread> threads = new ArrayList<>();
            for (String url : categoriesLinks) {
                Thread thread = new Thread(() -> processCategory(url));
                thread.start();
                threads.add(thread);
            }
            for (Thread thread : threads) {
                try {
                    thread.join();
                } catch (InterruptedException ignored) {}
            }
        } catch (Exception e) {
            log.error("Error during crawling process", e);
            CrawlLog crawlLog = new CrawlLog();
            crawlLog.setNewNewsCount(counter_new_news.get());
            crawlLog.setErrorMessage(FAILED);
            return;
        }
        CrawlLog crawlLog = new CrawlLog();
        StringBuilder error = new StringBuilder(SUCCESS);
        if (ifErrorCluster.get())
            error.append("Error cluster(s);");
        if (ifErrorCategory.get())
            error.append("Error category(ies);");
        if (ifErrorAuthor.get())
            error.append("Error author(s);");
        if (ifErrorNews.get())
            error.append("Error news;");
        crawlLog.setErrorMessage(error.toString());
        crawlLog.setNewNewsCount(counter_new_news.get());
        crawlLogRepository.save(crawlLog);
        System.out.println("end crawling....");
    }

    private Document fetchDocument(String url) throws IOException {
        int attempts = 0;
        IOException lastException = null;
        while (attempts < maxAttempts) {
            try {
                // Добавляем User-Agent и задержку
                Document doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                        .timeout(10_000)
                        .get();

                // Задержка между запросами (2 секунды)
                Thread.sleep(2000);
                return doc;

            } catch (InterruptedException ignored) {
            } catch (IOException e) {
                lastException = e;
                attempts++;
                if (attempts < maxAttempts) {
                    try {
                        Thread.sleep(TimeUnit.MINUTES.toMillis(1)); // Ждём 1 мин. при ошибке
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        if (lastException != null)
            throw lastException;
        throw new IOException();
    }

    private void processCategory(String url) {
        printTextInMultiThread("Start process cluster: " + url);
        try {
            List<String> newsLinks = new LinkedList<>();
            Document doc = fetchDocument(url);
            Element articleListing = doc.getElementById("_id_main_content");
            if (articleListing == null) {
                log.error("Can't find main content of category: {}", url);
                return;
            }
            Elements articleLinks = articleListing.select(
                    ".w_col_wide a[href], " +
                            ".w_col1 a[href], " +
                            ".w_col2 a[href], " +
                            ".w_col3 a[href], " +
                            ".b_newslist-digest a[href], " +
                            ".w_col_wide1 a[href], " +
                            ".w_col_wide2 a[href], " +
                            ".w_col_wide3 a[href]"
            );
            for (Element link : articleLinks) {
                String href = link.attr("href");
                if (href.startsWith("https://") && href.endsWith(".shtml"))
                    newsLinks.add(href);
                else if (href.endsWith(".shtml"))
                    newsLinks.add(baseUrl + href);
            }
            if (newsLinks.isEmpty()) {
                log.warn("Can't find news in category: {}", url);
                return;
            }
            for (String link: newsLinks) {
                if (processedNewsRightNow.contains(link)) {
                    printTextInMultiThread(String.format("News already processed by another Thread: %s", link));
                    continue;
                }
                Optional<News> oldNews = newsRepository.findByUrl(link);
                if (oldNews.isPresent() && !shouldReplace(oldNews.get())) {
                    printTextInMultiThread(String.format("News not expired: %s", link));
                    continue;
                }
                processedNewsRightNow.add(link);
                News news = processNews(link);
                if (news == null) {
                    printTextInMultiThread(String.format("Error while processed news: %s", link));
                    continue;
                }
                if (oldNews.isPresent())
                    try {
                        triggerUpdateNews(oldNews.get(), news);
                    } catch (Exception exception) {
                        log.error("Error during save news: {}", link, exception);
                    }
                else {
                    counter_new_news.addAndGet(1);
                    newsRepository.save(news);
                    printTextInMultiThread(String.format("Saved new news: %s", link));
                }
                processedNewsRightNow.remove(link);
            }
        } catch (Exception e) {
            log.error("Error during processing set of news: {}", url, e);
        }
        printTextInMultiThread("End process cluster: " + url);
    }
    private News processNews(String url) {
        News news = new News();
        news.setUrl(url);
        try {
            Document doc = fetchDocument(url);
            Element articleListing = doc.getElementById("_id_article");
            if (articleListing == null)
                throw new IOException("No article content found");

            Element timeElement = doc.selectFirst(".time[itemprop=datePublished]");
            if (timeElement == null)
                throw new IOException("Can't find time element");
            String datetime = timeElement.attr("datetime");
            LocalDateTime localDate = null;
            try {
                OffsetDateTime odt = OffsetDateTime.parse(datetime);
                localDate = odt.toLocalDateTime();
            } catch (Exception ignored) {}
            if (localDate == null)
                throw new IOException("Can't parse time");
            news.setPublicationDate(localDate);

            Category category = extractCategory(url);
            Optional<Category> oldCategory = categoryRepository.findByName(category.getName());
            if (oldCategory.isPresent())
                triggerUpdateCategory(oldCategory.get(), category);
            else
                categoryRepository.save(category);
            news.setCategory(category);

            Element authorLink = doc.selectFirst("span[itemprop=name] > a[itemprop=url]");
            Author author;
            if (authorLink == null)
                author = unknownAuthor;
            else
                author = processAuthor(baseUrl + authorLink.attr("href"));
            Optional<Author> oldAuthor = authorRepository.findByName(author.getName());
            if (oldAuthor.isPresent())
                triggerUpdateAuthor(oldAuthor.get(), author);
            else
                authorRepository.save(author);
            news.setAuthor(author);

            Element header = articleListing.selectFirst(".headline[itemprop=headline]");
            if ((header == null) || (header.text().isBlank())) {
                header = articleListing.selectFirst(".headline[itemprop=alternativeHeadline]");
                if ((header == null) || (header.text().isBlank()))
                    throw new IOException("Can't find headline");
            }
            news.setTitle(header.text());

            Element articleText = doc.selectFirst("div.b_article-text");
            if (articleText == null || articleText.text().isBlank())
                throw new IOException("Can't find articleText");
            StringBuilder sb = new StringBuilder();
            for (Element child : articleText.children()) {
                String tag = child.tagName();
                switch (tag) {
                    case "p":
                        try {
                            sb.append(child.text()).append("\n\n");
                        } catch (Exception ignored) {}
                        break;

                    case "h2":
                        try {
                            sb.append("\n").append(child.text().toUpperCase()).append("\n\n"); // например, заголовок капсом
                        } catch (Exception ignored) {}
                        break;

                    case "ul":
                        try {
                            for (Element li : child.select("li")) {
                                sb.append("• ").append(li.text()).append("\n");
                            }
                        } catch (Exception ignored) {}
                        sb.append("\n");
                        break;

                    default:
                        // если вдруг встретился какой-то другой тег — можно просто взять текст
                        try {
                            sb.append(child.text()).append("\n\n");
                        } catch (Exception ignored) {}
                        break;
                }
            }
            String articleContent = sb.toString().trim();
            if (articleContent.isBlank())
                throw new RuntimeException("Article content is empty");
            news.setContent(articleContent);
        } catch (Exception e) {
            log.error("Error during processing news: {}", url, e);
            ifErrorNews.set(true);
            return null;
        }
        return news;
    }
    private Author processAuthor(String url) throws IOException {
        Author author = new Author();
        try {
            Document doc = fetchDocument(url);
            Element authorDiv = doc.selectFirst(".author-info");
            if (authorDiv == null) {
                ifErrorAuthor.set(true);
                return unknownAuthor;
            }
            Element nameSpan = authorDiv.selectFirst("span[itemprop=name]");
            if ((nameSpan == null) || (nameSpan.text().isBlank())) {
                ifErrorAuthor.set(true);
                log.error("Empty name of author: {}", url);
                return unknownAuthor;
            }
            String authorName = nameSpan.text();
            Element email = authorDiv.selectFirst("a.author-mail");
            String emailAddr;
            if ((email == null) || (email.text().isBlank()))
                emailAddr = unknownEmail;
            else
                emailAddr = email.text();
            author.setName(authorName);
            author.setEmail(emailAddr);
        } catch (Exception e) {
            log.error("Error during processing author: {}", url, e);
            ifErrorAuthor.set(true);
            return unknownAuthor;
        }
        return author;
    }
    private Category extractCategory(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            String[] parts = path.split("/");
            if ((parts.length > 1) && (!parts[1].isBlank())) {
                return new Category(parts[1]); // "politics"
            }
        } catch (Exception ignored) {}
        ifErrorCategory.set(true);
        return unknownCategory;
    }
    private void triggerUpdateNews(News oldNews, News newNews) {
        newNews.setId(oldNews.getId());
        if (!(oldNews.getCategory().getId().equals(newNews.getCategory().getId())) ||
            !(oldNews.getTitle().equals(newNews.getTitle())) ||
            !(oldNews.getContent().equals(newNews.getContent())) ||
            !(oldNews.getAuthor().getId().equals(newNews.getAuthor().getId())) ||
            !(oldNews.getPublicationDate().equals(newNews.getPublicationDate())))
        {
            newsRepository.save(newNews);
            printTextInMultiThread(String.format("News updated: %s", newNews.getUrl()));
        }
        else {
            oldNews.setCreatedAt(LocalDateTime.now());
            newsRepository.save(oldNews);
            printTextInMultiThread(String.format("News up to date: %s", newNews.getUrl()));
        }
    }
    private void triggerUpdateAuthor(Author oldAuthor, Author newAuthor) {
        newAuthor.setId(oldAuthor.getId());
        if (!oldAuthor.getEmail().equals(newAuthor.getEmail()))
            authorRepository.save(newAuthor);
    }
    private void triggerUpdateCategory(Category oldCategory, Category newCategory) {
        newCategory.setId(oldCategory.getId());
        if (!oldCategory.getName().equals(newCategory.getName()))
            categoryRepository.save(newCategory);
    }
    public boolean shouldReplace(News oldNews) {
        Duration duration = Duration.between(oldNews.getCreatedAt(), LocalDateTime.now());
        return duration.toMinutes() >= expirationMinutes;
    }
    public static void printTextInMultiThread(String text) {
        synchronized (System.out) {
            System.out.println(Thread.currentThread().getName() + ": " + text);
        }
    }
}
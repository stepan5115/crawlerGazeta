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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class NewsCrawlerService {
    private final NewsRepository newsRepository;
    private final AuthorRepository authorRepository;
    private final CategoryRepository categoryRepository;
    private final CrawlLogRepository crawlLogRepository;

    int counter_error = 0;
    boolean ifErrorCategory = false;
    boolean ifErrorAuthor = false;
    boolean ifErrorNews = false;

    @Value("${crawler.base-url}")
    private String baseUrl;

    @Value("${crawler.interval-minutes}")
    private int intervalMinutes;

    @Value("${crawler.max-attempts}")
    private int maxAttempts;

    @Value("${unknown_author}")
    private String unknownAuthorName;

    @Value("${unknown_email}")
    private String unknownEmail;

    @Value("${unknown_category}")
    private String unknownCategoryName;

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
    @Scheduled(fixedRateString = "${crawler.interval-minutes}", timeUnit = TimeUnit.MINUTES)
    public void crawlNews() {
        System.out.println("start crawling....");
        counter_error = 0;
        ifErrorCategory = false;
        ifErrorAuthor = false;
        ifErrorNews = false;
        List<String> categoriesLinks = new LinkedList<>();
        try {
            Document doc = fetchDocument(baseUrl);
            Element menuContent = doc.selectFirst("div.b_menu-content");
            if (menuContent == null) {
                System.out.println("No category found");
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
            for (String url : categoriesLinks) {
                processCategory(url);
            }
        } catch (Exception e) {
            log.error("Error during crawling process", e);
        }
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

    private void processCategory(String url) throws IOException {
        System.out.println("\tStart processing category: " + url);
        try {
            List<String> newsLinks = new LinkedList<>();
            Document doc = fetchDocument(url);
            Element articleListing = doc.getElementById("_id_main_content");
            if (articleListing == null) {
                System.out.println("\tNo news in category found");
                System.out.println("\tEnd processing category: " + url);
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
                System.out.println("\tNo news in category found");
                System.out.println("\tEnd processing category: " + url);
                return;
            }
            for (String link: newsLinks) {
                News news = processNews(link);
                if (news == null)
                    continue;
                Optional<News> oldNews = newsRepository.findByUrl(link);
                if (oldNews.isPresent())
                    try {
                        triggerUpdateNews(oldNews.get(), news);
                    } catch (Exception exception) {
                        log.error("Error during save news", exception);
                    }
                else {
                    newsRepository.save(news);
                    System.out.println("\t\tSaved new news: " + news.getTitle());
                }
            }
        } catch (Exception e) {
            log.error("Error during processing category {}", url, e);
            ifErrorCategory = true;
        }
        System.out.println("\tEnd processing category: " + url);
    }
    private News processNews(String url) throws IOException {
        System.out.println("\t\tStart processing news: " + url);
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
            else {
                categoryRepository.save(category);
                System.out.println("\t\tCategory creadet: " + category.getName());
            }
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
            else {
                authorRepository.save(author);
                System.out.println("\t\tAuthor created: " + author.getName());
            }
            news.setAuthor(author);
            Element header = articleListing.selectFirst(".headline[itemprop=headline]");
            if ((header == null) || (header.text().isBlank())) {
                header = articleListing.selectFirst(".headline[itemprop=alternativeHeadline]");
                if ((header == null) || (header.text().isBlank()))
                    throw new IOException("Can't find headline");
            }
            news.setTitle(header.text());
            Element subheader = doc.selectFirst(".subheader[itemprop=alternativeHeadline]");
            if ((subheader == null) || (subheader.text().isBlank()))
                throw new IOException("Can't find subHeader");
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
            //System.out.println(news);
        } catch (Exception e) {
            log.error("Error during processing news: {}", url, e);
            System.out.println("\t\tCan't process news: " + url);
            ifErrorNews = true;
            return null;
        }
        System.out.println("\t\tEnd processing news: " + url);
        return news;
    }
    private Author processAuthor(String url) throws IOException {
        System.out.println("\t\t\tStart processing author: " + url);
        Author author = new Author();
        try {
            Document doc = fetchDocument(url);
            Element authorDiv = doc.selectFirst(".author-info");
            if (authorDiv == null) {
                System.out.println("\t\t\tCan't find author page");
                ifErrorAuthor = true;
                return unknownAuthor;
            }
            Element nameSpan = authorDiv.selectFirst("span[itemprop=name] > span");
            if ((nameSpan == null) || (nameSpan.text().isBlank())) {
                System.out.println("\t\t\tUnknown author");
                ifErrorAuthor = true;
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
            System.out.println("\t\t\tauthor name: " + author.getName());
            System.out.println("\t\t\tauthor email: " + author.getEmail());
        } catch (Exception e) {
            log.error("Error during processing author: {}", url, e);
            System.out.println("\t\t\tUnknown author: " + url);
            ifErrorAuthor = true;
            return unknownAuthor;
        }
        System.out.println("\t\t\tEnd processing author: " + url);
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
        return unknownCategory;
    }
    private void triggerUpdateNews(News oldNews, News newNews) {
        newNews.setId(oldNews.getId());
        if (!(oldNews.getCategory().getId().equals(newNews.getCategory().getId())) ||
            !(oldNews.getTitle().equals(newNews.getTitle())) ||
            !(oldNews.getContent().equals(newNews.getContent())) ||
            !(oldNews.getAuthor().getName().equals(newNews.getAuthor().getName())) ||
            !(oldNews.getPublicationDate().equals(newNews.getPublicationDate())))
        {
            newsRepository.save(newNews);
            System.out.println("\t\tNews updated: " + newNews.getTitle());
        }
        else {
            System.out.println("\t\tNews up to date: " + newNews.getTitle());
        }
    }
    private void triggerUpdateAuthor(Author oldAuthor, Author newAuthor) {
        newAuthor.setId(oldAuthor.getId());
        if (!oldAuthor.getEmail().equals(newAuthor.getEmail())) {
            authorRepository.save(newAuthor);
            System.out.println("\t\t\tAuthor updated: " + newAuthor.getName());
        } else {
            System.out.println("\t\t\tAuthor up to date: " + newAuthor.getName());
        }
    }
    private void triggerUpdateCategory(Category oldCategory, Category newCategory) {
        newCategory.setId(oldCategory.getId());
        if (!oldCategory.getName().equals(newCategory.getName())) {
            categoryRepository.save(newCategory);
            System.out.println("\t\t\tCategory updated: " + newCategory.getName());
        }
        else
            System.out.println("\t\t\tCategory up to date: " + newCategory.getName());
    }
}
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
    //string for status result of crawling
    public final static String SUCCESS = "Success: ";
    public final static String FAILED = "Failed";
    //repositories for work with DataBase
    private final NewsRepository newsRepository;
    private final AuthorRepository authorRepository;
    private final CategoryRepository categoryRepository;
    private final CrawlLogRepository crawlLogRepository;
    //flags and counter for detailed crawler log
    private final AtomicInteger counter_new_news = new AtomicInteger(0);
    private final AtomicBoolean ifErrorCluster = new AtomicBoolean(false);
    private final AtomicBoolean ifErrorCategory = new AtomicBoolean(false);
    private final AtomicBoolean ifErrorAuthor = new AtomicBoolean(false);
    private final AtomicBoolean ifErrorNews = new AtomicBoolean(false);
    //base url (main page of website)
    @Value("${crawler.base-url}")
    private String baseUrl;
    //the number of minutes for the news to become expired
    @Value("${crawler.interval-outing-minutes}")
    private int expirationMinutes;
    //the number of minutes for the periodic start of the crawler
    @Value("${crawler.interval-minutes}")
    private int intervalCrawlMinutes;
    //count max attempt of error request for server
    @Value("${crawler.max-attempts}")
    private int maxAttempts;
    //name for unknownAuthor
    @Value("${unknown_author}")
    private String unknownAuthorName;
    //email for unknownAuthor
    @Value("${unknown_email}")
    private String unknownEmail;
    //name for unknown category
    @Value("${unknown_category}")
    private String unknownCategoryName;
    //a list that supports multithreading and stores the currently processed news
    private final ConcurrentLinkedQueue<String> processedNewsRightNow = new ConcurrentLinkedQueue<>();
    //the category that applies if the real news category cannot be parsed
    private Category unknownCategory;
    //the author that is applied if it is not possible to parse the real author of the news
    private Author unknownAuthor;
    //this method is needed to initialize the author and
    //category after dependency injection using @Value.
    @PostConstruct
    private void init() {
        this.unknownAuthor = new Author(unknownAuthorName, unknownEmail);
        this.unknownCategory = new Category(unknownCategoryName);
    }
    //The @Scheduled annotation from Spring is used to run the method regularly on a schedule.
    //fixedRateString - Running the method at a fixed interval after the start of the previous call.
    //TimeUnit - By default, fixedRate and fixedDelay work in milliseconds, but we change them to minutes.
    @Scheduled(fixedRateString = "${crawler.interval-func}", timeUnit = TimeUnit.MINUTES)
    public void crawlNews() {
        //Get last log from Database
        Optional<CrawlLog> lastLog = crawlLogRepository.findFirstByOrderByCrawlTimeDesc();
        //Check last log for existing, error or expired to start new crawling
        if (lastLog.isPresent() && !lastLog.get().getErrorMessage().equals(FAILED) &&
                Duration.between(lastLog.get().getCrawlTime(), LocalDateTime.now()).toMinutes() <= intervalCrawlMinutes) {
            System.out.println("Last crawl not expired");
            return;
        }
        //start crawling
        System.out.println("start crawling....");
        //set flags and counter for crawl log to default values
        counter_new_news.set(0);
        ifErrorCluster.set(false);
        ifErrorCategory.set(false);
        ifErrorAuthor.set(false);
        ifErrorNews.set(false);
        //clear news marked as processed
        processedNewsRightNow.clear();
        //create list to store links on categories
        List<String> categoriesLinks = new LinkedList<>();
        try {
            //get connection to main page
            Document doc = fetchDocument(baseUrl);
            //find element with class "b_menu-content" on main page
            Element menuContent = doc.selectFirst("div.b_menu-content");
            //if program can't find this element, stop crawling
            if (menuContent == null) {
                log.error("No menu content find on base url: {}", baseUrl);
                //create crawl log about failed
                CrawlLog crawlLog = new CrawlLog();
                crawlLog.setNewNewsCount(0);
                crawlLog.setErrorMessage(FAILED);
                return;
            }
            //get all elements with class "b_menu-item" and get tags with attr "href" from them
            Elements menuItems = menuContent.select("div.b_menu-item a[href]");
            for (Element menuItem : menuItems) {
                //get attr "href"
                String href = menuItem.attr("href");
                //filter (bad categories that can't be parsing)
                if (!href.equals("/subjects/civilization/") &&
                    !href.equals("/history.shtml") &&
                    !href.equals("/about/") &&
                    !href.equals("/quiz/") &&
                    !href.equals("/infographics/") &&
                    !href.equals("/photo/") &&
                    !href.equals("/children/")
                ) {
                    //add category in list
                    categoriesLinks.add(baseUrl + href);
                }
            }
            //list for threads
            List<Thread> threads = new ArrayList<>();
            for (String url : categoriesLinks) {
                //start all threads
                Thread thread = new Thread(() -> processCategory(url));
                thread.start();
                threads.add(thread);
            }
            for (Thread thread : threads) {
                try {
                    //wait all threads
                    thread.join();
                } catch (InterruptedException ignored) {}
            }
        } catch (Exception e) {
            //if error while crawling create failed crawl log
            log.error("Error during crawling process", e);
            CrawlLog crawlLog = new CrawlLog();
            crawlLog.setNewNewsCount(counter_new_news.get());
            crawlLog.setErrorMessage(FAILED);
            //clear news marked as processed
            processedNewsRightNow.clear();
            return;
        }
        //if success create success crawl log with detailed information
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
        //clear news marked as processed
        processedNewsRightNow.clear();
        //end crawling
        System.out.println("end crawling....");
    }
    //method for connect with pages
    private Document fetchDocument(String url) throws IOException {
        //count of attempts
        int attempts = 0;
        IOException lastException = null;
        //trying to connect until we reach the attempt limit
        while (attempts < maxAttempts) {
            try {
                //Add User-Agent and delay
                Document doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                        .timeout(10_000)
                        .get();

                //delay between request (2 seconds)
                Thread.sleep(2000);
                return doc;

            } catch (InterruptedException ignored) {
            } catch (IOException e) {
                //continue try to connect or break if reach limit
                lastException = e;
                attempts++;
                if (attempts < maxAttempts) {
                    try {
                        Thread.sleep(TimeUnit.MINUTES.toMillis(1));
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
        if (lastException != null)
            throw lastException;
        throw new IOException();
    }
    //process category (get from main menu)
    private void processCategory(String url) {
        printTextInMultiThread("Start process cluster: " + url);
        try {
            //try to connect to page
            List<String> newsLinks = new LinkedList<>();
            Document doc = fetchDocument(url);
            //get element with id "_id_main_content" and break if this element not find
            Element articleListing = doc.getElementById("_id_main_content");
            if (articleListing == null) {
                log.error("Can't find main content of category: {}", url);
                return;
            }
            //get all elements with classes that used for news elements
            //and get tags with attr href
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
            //processed all found links
            for (Element link : articleLinks) {
                //some links have entire link, some - only part
                //processed both cases
                String href = link.attr("href");
                if (href.startsWith("https://") && href.endsWith(".shtml"))
                    newsLinks.add(href);
                else if (href.endsWith(".shtml"))
                    newsLinks.add(baseUrl + href);
            }
            //if no news in category
            if (newsLinks.isEmpty()) {
                log.warn("Can't find news in category: {}", url);
                return;
            }
            //process all news
            for (String link: newsLinks) {
                //check if another thread process this news
                if (processedNewsRightNow.contains(link)) {
                    printTextInMultiThread(String.format("News already processed by another Thread: %s", link));
                    continue;
                }
                //check if news exist and not expired
                Optional<News> oldNews = newsRepository.findByUrl(link);
                if (oldNews.isPresent() && !shouldReplace(oldNews.get())) {
                    printTextInMultiThread(String.format("News not expired: %s", link));
                    continue;
                }
                //marking the news as processed
                processedNewsRightNow.add(link);
                //process news
                News news = processNews(link);
                //if bad news processing
                if (news == null) {
                    printTextInMultiThread(String.format("Error while processed news: %s", link));
                    continue;
                }
                //if success process, then try update or create news
                if (oldNews.isPresent())
                    try {
                        triggerUpdateNews(oldNews.get(), news);
                    } catch (Exception exception) {
                        log.error("Error during update news: {}", link, exception);
                    }
                else {
                    //increase the counter
                    counter_new_news.addAndGet(1);
                    newsRepository.save(news);
                    printTextInMultiThread(String.format("Saved new news: %s", link));
                }
            }
        } catch (Exception e) {
            log.error("Error during processing set of news: {}", url, e);
        }
        printTextInMultiThread("End process cluster: " + url);
    }
    //process news
    public News processNews(String url) {
        //create news and set url for news
        News news = new News();
        news.setUrl(url);
        try {
            //connect to page
            Document doc = fetchDocument(url);
            //get element with id="_id_article" and break if not exist
            Element articleListing = doc.getElementById("_id_article");
            if (articleListing == null)
                throw new IOException("No article content found");
            //get time of publication from articleListing element
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
            //try to get category from url
            Category category = extractCategory(url);
            //check category for existing and update or create if needed
            Optional<Category> oldCategory = categoryRepository.findByName(category.getName());
            if (oldCategory.isPresent())
                triggerUpdateCategory(oldCategory.get(), category);
            else
                categoryRepository.save(category);
            news.setCategory(category);
            //try to get author link from articleListing element
            Element authorLink = doc.selectFirst("span[itemprop=name] > a[itemprop=url]");
            //process author by link
            Author author;
            if (authorLink == null)
                author = unknownAuthor;
            else
                author = processAuthor(baseUrl + authorLink.attr("href"));
            Optional<Author> oldAuthor = authorRepository.findByName(author.getName());
            //create or update author if needed
            if (oldAuthor.isPresent())
                triggerUpdateAuthor(oldAuthor.get(), author);
            else
                authorRepository.save(author);
            news.setAuthor(author);
            //try to get header from articleListing element
            Element header = articleListing.selectFirst(".headline[itemprop=headline]");
            if ((header == null) || (header.text().isBlank())) {
                header = articleListing.selectFirst(".headline[itemprop=alternativeHeadline]");
                if ((header == null) || (header.text().isBlank()))
                    throw new IOException("Can't find headline");
            }
            news.setTitle(header.text());
            //try to construct article text from many elements
            Element articleText = doc.selectFirst("div.b_article-text");
            Element intro = doc.selectFirst(".intro");
            if (articleText == null || articleText.text().isBlank())
                throw new IOException("Can't find articleText");
            StringBuilder sb = new StringBuilder();
            if ((intro != null) && (!intro.text().isBlank())) {
                sb.append(intro.text()).append("\n\n");
            }
            for (Element child : articleText.children()) {
                String tag = child.tagName();
                //custom processed for some class of elements
                switch (tag) {
                    case "h2":
                        try {
                            sb.append("\n").append(child.text().toUpperCase()).append("\n\n");
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
            //if error then set flag about it
            log.error("Error during processing news: {}", url, e);
            ifErrorNews.set(true);
            return null;
        }
        return news;
    }
    //process author
    private Author processAuthor(String url) {
        Author author = new Author();
        try {
            //try to connect to page
            Document doc = fetchDocument(url);
            //get element with class "author-info" and return unknownAuthor if these
            //element not exist
            Element authorDiv = doc.selectFirst(".author-info");
            if (authorDiv == null) {
                ifErrorAuthor.set(true);
                return unknownAuthor;
            }
            //try to get name and return unknown author if name not exist
            Element nameSpan = authorDiv.selectFirst("span[itemprop=name]");
            if ((nameSpan == null) || (nameSpan.text().isBlank())) {
                ifErrorAuthor.set(true);
                log.error("Empty name of author: {}", url);
                return unknownAuthor;
            }
            //try extract email and replace for unknownEmail if not exist
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
            //if error then return unknownAuthor
            log.error("Error during processing author: {}", url, e);
            ifErrorAuthor.set(true);
            return unknownAuthor;
        }
        return author;
    }
    //extract category from url of news
    private Category extractCategory(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            String[] parts = path.split("/");
            if ((parts.length > 1) && (!parts[1].isBlank())) {
                return new Category(parts[1]); // "politics"
            }
        } catch (Exception ignored) {}
        //if program can't extract then return unknownCategory
        ifErrorCategory.set(true);
        return unknownCategory;
    }
    //method to check if news must be updated
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
    //method to check if author must be updated
    private void triggerUpdateAuthor(Author oldAuthor, Author newAuthor) {
        newAuthor.setId(oldAuthor.getId());
        if (!oldAuthor.getEmail().equals(newAuthor.getEmail()))
            authorRepository.save(newAuthor);
    }
    //method to check if category must be updated
    private void triggerUpdateCategory(Category oldCategory, Category newCategory) {
        newCategory.setId(oldCategory.getId());
        if (!oldCategory.getName().equals(newCategory.getName()))
            categoryRepository.save(newCategory);
    }
    //method to check if news should be processed, or it's not expired
    public boolean shouldReplace(News oldNews) {
        Duration duration = Duration.between(oldNews.getCreatedAt(), LocalDateTime.now());
        return duration.toMinutes() >= expirationMinutes;
    }
    //method to print text in console in multithreading
    public static void printTextInMultiThread(String text) {
        synchronized (System.out) {
            System.out.println(Thread.currentThread().getName() + ": " + text);
        }
    }
}
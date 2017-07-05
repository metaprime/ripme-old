package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.jsoup.Connection.Response;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

public class SankakuComplexRipper extends AbstractHTMLRipper {
    public static Logger logger;

    private Document albumDoc = null;
    private Map<String,String> cookies = new HashMap<String,String>();

    public SankakuComplexRipper(URL url) throws IOException {
        super(url);
        logger = Logger.getLogger(SankakuComplexRipper.class);
    }

    @Override
    public String getHost() {
        return "sankakucomplex";
    }

    @Override
    public String getDomain() {
        return "sankakucomplex.com";
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p = Pattern.compile("^https?://(?:([a-zA-Z0-9]+)\\.)?sankakucomplex\\.com/.*tags=([^&]+).*$");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            String tagName = m.group(1) + "_" + m.group(2);
            try {
                return URLDecoder.decode(tagName, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new MalformedURLException("Cannot decode tag name '" + tagName + "'");
            }
        }
        throw new MalformedURLException("Expected sankakucomplex.com URL format: " +
                        "idol.sankakucomplex.com?...&tags=something... - got " +
                        url + "instead");
    }

    @Override
    public Document getFirstPage() throws IOException {
        if (albumDoc == null) {
            Response resp = Http.url(url).response();
            cookies.putAll(resp.cookies());
            albumDoc = resp.parse();
        }
        return albumDoc;
    }

    @Override
    public List<String> getURLsFromPage(Document doc) {
        List<String> imageURLs = new ArrayList<String>();
        // Image URLs are basically thumbnail URLs with a different domain, a simple
        // path replacement, and a ?xxxxxx post ID at the end (obtainable from the href)
        for (Element thumbSpan : doc.select("div.content > div > span.thumb")) {
            String postId = thumbSpan.attr("id").replaceAll("p", "");
            Element thumb = thumbSpan.getElementsByTag("img").first();
            String image = thumb.attr("abs:src")
                                .replace(".sankakucomplex.com/data/preview",
                                         "s.sankakucomplex.com/data") + "?" + postId;
            imageURLs.add(image);
        }
        return imageURLs;
    }

    @Override
    public void downloadURL(URL url, int index) {
        // This method is called on each of the urls returned from getURLsFromPage.
        // Mock up the URL of the post page based on the post ID at the end of the URL.
        String postId = url.toExternalForm().replaceAll(".*\\?", "");
        addURLToDownload(url, postId + "_", "", "", null);
    }

    private List<String> getPostURLsFromPage(Document doc) {
        List<String> imageURLs = new ArrayList<String>();
        for (Element thumbSpan : doc.select("div.content > div > span.thumb")) {
            String postId = thumbSpan.attr("id").replaceAll("p", "");
            String domain = doc.location().replaceFirst("(.*\\.sankakucomplex.com).*", "$1");
            String postUrl = domain + "/post/show/" + postId;
            logger.info("post URL is: " + postUrl);
            imageURLs.add(postUrl);
        }
        return imageURLs;
    }

    private void downloadFromPost(URL url) {
        try {
            Document postDoc = Http.url(url).cookies(cookies).get();

            String imageLink = postDoc.select("div#post-content a#image-link").attr("href");
            if (imageLink.equals("")) {
                imageLink = postDoc.select("#image").attr("src");
            }
            if (imageLink.equals("")) {
                logger.error("Couldn't find an image URL");
                return; // nothing to download
            }

            imageLink = "http:" + imageLink; // imageLink is a full path to an image starting with "//"
            logger.info("image link is: " + imageLink);

            // create a new URL for the actual download
            url = new URL(imageLink);

            // queue the picture to download so we do something useful during the long delay below
            downloadURL(url, 0);

            // We're making a lot of extra requests now that we have to check every page
            // to find the actual URL instead of guessing the file extension.
            // Delay between requests to ensure we don't get rate-limited.
            // 2500 was not enough when checking all pages, so using 3000 for now.
            sleep(3000);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void downloadErrored(URL url, String reason) {
        // Because of rate-limiting, we can't afford to check every single post URL.
        // Even with delays, we are still just making too many requests per minute.
        // So we would have to make the ripper crazily slow, or we can get clever here.
        // The majority of the time, jpg is actually the right choice.
        // Initially, we try to download jpg. If jpg fails to download correctly,
        // then fallback on checking the post for the actual image URL.

        // If we were trying to download a jpg and we got an error, try checking the corresponding post instead
        if (url.getPath().matches("\\.jpg\\?")) {
            String postId = url.getPath().replaceFirst("^.*\\?(\\d+)$", "$1");
            String domain = albumDoc.location().replaceFirst("(.*\\.sankakucomplex.com).*", "$1");
            String postUrl = domain + "/post/show/" + postId;

            itemsPending.remove(url);
            try {
                logger.info("Checking post URL: " + postUrl);
                downloadFromPost(new URL(postUrl));
            } catch (MalformedURLException e) {
                super.downloadErrored(url, reason);
                return;
            }
        } else {
            // If we tried to download a post and it failed, then we give up
            super.downloadErrored(url, reason);
        }
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        Element pagination = doc.select("div.pagination").first();
        if (pagination.hasAttr("next-page-url")) {
            return Http.url(pagination.attr("abs:next-page-url")).cookies(cookies).get();
        } else {
            return null;
        }
    }
}

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
        // We will rip pictures during the delay so we will just call to
        // downloadURL directly instead of returning a list of URLs.
        List<String> imageURLs = new ArrayList<String>();

        for (Element thumbSpan : doc.select("div.content > div > span.thumb")) {
            String postId = thumbSpan.attr("id").replaceAll("p", "");
            String domain = doc.location().replaceFirst("(.*\\.sankakucomplex.com).*", "$1");
            String postUrl = domain + "/post/show/" + postId;
            logger.info("post URL is: " + postUrl);

            try {
                Document postDoc = Http.url(postUrl).cookies(cookies).get();

                String imageLink = postDoc.select("div#post-content a#image-link").attr("href");
                if (imageLink.equals("")) {
                    imageLink = postDoc.select("#image").attr("src");
                }
                if (imageLink.equals("")) {
                    logger.error("Couldn't find an image URL");
                    continue; // don't add picture
                }

                imageLink = "http:" + imageLink;
                logger.info("image link is: " + imageLink);

                // queue the picture to download so we do something useful during the long delay below
                downloadURL(new URL(imageLink), 0);

                // We're making a lot of extra requests now that we have to check every page
                // to find the actual URL instead of guessing the file extension.
                // Delay between requests to ensure we don't get rate-limited.
                // 1000 wasn't enough, so using 2000 for now.
                sleep(2000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return null; // see comment above
    }

    @Override
    public void downloadURL(URL url, int index) {
        // Mock up the URL of the post page based on the post ID at the end of the URL.
        String postId = url.toExternalForm().replaceAll(".*\\?", "");
        addURLToDownload(url, postId + "_", "", "", null);
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

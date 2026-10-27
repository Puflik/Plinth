import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Планировщик Ю3б: записывает обмены экстрактора с YouTube на настольной JVM. Не в репозитории. */
public class Recorder {
    static Path out;
    static int seq = 0;

    static class Rec extends Downloader {
        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            seq++;
            String method = request.httpMethod();
            String url = request.url();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0");
            Map<String, List<String>> reqHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : request.headers().entrySet()) {
                boolean first = true;
                for (String v : e.getValue()) {
                    if (first) c.setRequestProperty(e.getKey(), v); else c.addRequestProperty(e.getKey(), v);
                    first = false;
                }
                reqHeaders.put(e.getKey(), e.getValue());
            }
            c.setRequestProperty("Accept-Encoding", "gzip");
            byte[] data = request.dataToSend();
            if (data != null) {
                c.setDoOutput(true);
                c.getOutputStream().write(data);
            }
            int status = c.getResponseCode();
            InputStream raw = status >= 400 ? c.getErrorStream() : c.getInputStream();
            boolean gzip = "gzip".equalsIgnoreCase(c.getHeaderField("Content-Encoding"));
            byte[] body = new byte[0];
            if (raw != null && !"HEAD".equals(method)) {
                try (InputStream in = gzip ? new GZIPInputStream(raw) : raw) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) >= 0) bo.write(buf, 0, n);
                    body = bo.toByteArray();
                }
            }
            Map<String, List<String>> respHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null) respHeaders.put(e.getKey(), e.getValue());
            }
            String latest = c.getURL().toString();
            String text = new String(body, StandardCharsets.UTF_8);
            save(method, url, reqHeaders, data == null ? null : new String(data, StandardCharsets.UTF_8), status, respHeaders, text, latest);
            System.out.printf("  [%03d] %s %s -> %d %dB%n", seq, method, shorten(url), status, body.length);
            String message = c.getResponseMessage();
            c.disconnect();
            return new Response(status, message, respHeaders, text, latest);
        }
    }

    static String q(String v) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch)); else sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }

    static String shorten(String url) {
        int q = url.indexOf('?');
        return (q < 0 ? url : url.substring(0, q)).replace("https://", "");
    }

    static void save(String method, String url, Map<String, List<String>> reqHeaders, String reqBody, int status,
                     Map<String, List<String>> respHeaders, String body, String latestUrl) throws IOException {
        Files.createDirectories(out);
        String json = "{\n  \"seq\": " + seq + ",\n  \"method\": " + q(method) + ",\n  \"url\": " + q(url)
                + ",\n  \"latestUrl\": " + q(latestUrl) + ",\n  \"requestBody\": " + (reqBody == null ? "null" : q(reqBody))
                + ",\n  \"status\": " + status + ",\n  \"responseBody\": " + q(body) + "\n}\n";
        Files.writeString(out.resolve(String.format("%03d.json", seq)), json, StandardCharsets.UTF_8);
        StringBuilder meta = new StringBuilder();
        meta.append(method).append(' ').append(url).append('\n');
        reqHeaders.forEach((k, v) -> meta.append("> ").append(k).append(": ").append(v).append('\n'));
        respHeaders.forEach((k, v) -> meta.append("< ").append(k).append(": ").append(v).append('\n'));
        Files.writeString(out.resolve(String.format("%03d.meta.txt", seq)), meta.toString(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
        String mode = args[0];
        out = Paths.get(System.getProperty("out", "out"), tag(args));
        NewPipe.init(new Rec(), Localization.DEFAULT, ContentCountry.DEFAULT);
        try {
            switch (mode) {
                case "search": search(arg(args[1]), args[2]); break;
                case "stream": stream(args[1], args.length > 2 ? Integer.parseInt(args[2]) : 2); break;
                case "playlist": playlist(args[1]); break;
                case "mix": mix(args[1], args.length > 2 ? Integer.parseInt(args[2]) : 2); break;
                default: throw new IllegalArgumentException(mode);
            }
        } catch (Throwable t) {
            System.out.println("FAILED: " + t);
            t.printStackTrace(System.out);
        }
    }

    static String arg(String a) throws IOException {
        return a.startsWith("@") ? Files.readString(Paths.get(a.substring(1)), StandardCharsets.UTF_8).trim() : a;
    }

    static String tag(String[] args) {
        StringBuilder sb = new StringBuilder(args[0]);
        for (int i = 1; i < args.length; i++) sb.append('_').append((args[i].startsWith("@") ? "q" + (i) : args[i]).replaceAll("[^A-Za-z0-9_-]", "x"));
        return sb.toString();
    }

    static void search(String query, String kind) throws Exception {
        String filter = kind.equals("songs") ? YoutubeSearchQueryHandlerFactory.MUSIC_SONGS : YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS;
        SearchExtractor ex = ServiceList.YouTube.getSearchExtractor(query, List.of(filter), "");
        ex.fetchPage();
        List<InfoItem> items = ex.getInitialPage().getItems();
        System.out.println("items: " + items.size());
        int i = 0;
        for (InfoItem it : items) {
            if (it instanceof StreamInfoItem) {
                StreamInfoItem s = (StreamInfoItem) it;
                System.out.printf("#%d song '%s' / '%s' / %ds / %s%n", i, s.getName(), s.getUploaderName(), s.getDuration(), s.getUrl());
            } else if (it instanceof PlaylistInfoItem) {
                PlaylistInfoItem p = (PlaylistInfoItem) it;
                System.out.printf("#%d album '%s' / '%s' / count=%d / %s%n", i, p.getName(), p.getUploaderName(), p.getStreamCount(), p.getUrl());
            } else {
                System.out.printf("#%d %s '%s'%n", i, it.getClass().getSimpleName(), it.getName());
            }
            i++;
        }
    }

    static void stream(String id, int rounds) throws Exception {
        for (int r = 1; r <= rounds; r++) {
            System.out.println("--- round " + r);
            StreamExtractor ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=" + id);
            ex.fetchPage();
            System.out.printf("name='%s' uploader='%s' length=%d%n", ex.getName(), ex.getUploaderName(), ex.getLength());
            for (AudioStream a : ex.getAudioStreams()) {
                String u = a.getContent();
                System.out.printf("  itag=%d fmt=%s avg=%d codec=%s c=%s%n", a.getItag(), a.getFormat(), a.getAverageBitrate(), a.getCodec(), param(u, "c"));
            }
        }
    }

    static String param(String url, String name) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        for (String kv : url.substring(q + 1).split("&")) {
            if (kv.startsWith(name + "=")) return kv.substring(name.length() + 1);
        }
        return null;
    }

    static String safe(java.util.concurrent.Callable<Object> c) {
        try { return String.valueOf(c.call()); } catch (Throwable t) { return "!" + t.getClass().getSimpleName() + ":" + t.getMessage(); }
    }

    static void playlist(String id) throws Exception {
        PlaylistExtractor ex = ServiceList.YouTube.getPlaylistExtractor("https://www.youtube.com/playlist?list=" + id);
        ex.fetchPage();
        System.out.printf("class=%s name='%s' uploader='%s' streamCount=%s thumbs=%s%n", ex.getClass().getSimpleName(),
                safe(ex::getName), safe(ex::getUploaderName), safe(ex::getStreamCount), safe(() -> ex.getThumbnails().size()));
        System.out.println("subChannel=" + safe(ex::getSubChannelName) + " desc=" + safe(() -> ex.getDescription().getContent()));
        List<StreamInfoItem> items = ex.getInitialPage().getItems();
        int i = 0;
        for (StreamInfoItem s : items) {
            System.out.printf("  #%d '%s' / '%s' / %ds / %s%n", i++, s.getName(), s.getUploaderName(), s.getDuration(), s.getUrl());
        }
        System.out.println("hasNextPage=" + ex.getInitialPage().hasNextPage());
    }

    /**
     * Mix `RD<видео>` (Ю4б): первая страница и ещё `pages - 1` через `getPage`, как делает источник приложения.
     * Записывает: `sw.js` (один раз на JVM) и `next` на каждую страницу.
     */
    static void mix(String id, int pages) throws Exception {
        PlaylistExtractor ex = ServiceList.YouTube.getPlaylistExtractor("https://www.youtube.com/watch?v=" + id + "&list=RD" + id);
        ex.fetchPage();
        System.out.printf("class=%s name='%s'%n", ex.getClass().getSimpleName(), safe(ex::getName));
        ListExtractor.InfoItemsPage<StreamInfoItem> page = ex.getInitialPage();
        for (int p = 1; ; p++) {
            System.out.printf("--- page %d: %d items, %d errors, hasNext=%s%n", p, page.getItems().size(), page.getErrors().size(), page.hasNextPage());
            int i = 0;
            for (StreamInfoItem s : page.getItems()) {
                System.out.printf("  #%d %s '%s' / '%s' / %ds / %s%n", i++, s.getStreamType(), s.getName(), s.getUploaderName(), s.getDuration(), s.getUrl());
            }
            if (p >= pages || !page.hasNextPage()) break;
            page = ex.getPage(page.getNextPage());
        }
    }
}

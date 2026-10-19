import org.schabi.newpipe.extractor.InfoItem;
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
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Планировщик Ю3б: проигрывает урезанные записи через настоящий экстрактор. Не в репозитории. */
public class Replay {
    static Path fix;

    record Route(String name, String method, String host, String path, List<String> contains, List<String> files, int[] served) {}

    static List<Route> routes = new ArrayList<>();
    static Map<String, String> override = new HashMap<>();

    static class Rep extends Downloader {
        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            URI uri = URI.create(request.url());
            String body = request.dataToSend() == null ? "" : new String(request.dataToSend(), StandardCharsets.UTF_8);
            String hay = request.url() + "\n" + body;
            for (Route r : routes) {
                if (!r.method().equals(request.httpMethod())) continue;
                if (!r.host().equals(uri.getHost())) continue;
                if (!r.path().equals(uri.getPath())) continue;
                boolean all = true;
                for (String c : r.contains()) if (!hay.contains(c)) { all = false; break; }
                if (!all) continue;
                int i = Math.min(r.served()[0]++, r.files().size() - 1);
                String file = override.getOrDefault(r.name(), r.files().get(i));
                String text = Files.readString(fix.resolve(file), StandardCharsets.UTF_8);
                System.out.printf("    <- %s %s%s => %s (%dB)%n", request.httpMethod(), uri.getHost(), uri.getPath(), file, text.length());
                return new Response(200, "OK", Map.of(), text, request.url());
            }
            throw new AssertionError("no route for " + request.httpMethod() + " " + request.url() + " body=" + body.substring(0, Math.min(300, body.length())));
        }
    }

    static void load() throws IOException {
        for (String line : Files.readAllLines(fix.resolve("routes.tsv"), StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\t", -1);
            routes.add(new Route(p[0], p[1], p[2], p[3],
                    p[4].isEmpty() ? List.of() : List.of(p[4].split(";;")),
                    List.of(p[5].split("\\|")), new int[]{0}));
        }
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
        fix = Paths.get(args[0]);
        for (int i = 2; i < args.length; i++) { String[] kv = args[i].split("=", 2); override.put(kv[0], kv[1]); }
        load();
        NewPipe.init(new Rep(), Localization.DEFAULT, ContentCountry.DEFAULT);
        String[] a = args[1].split(":", 2);
        try {
            switch (a[0]) {
                case "songs": search(a[1], YoutubeSearchQueryHandlerFactory.MUSIC_SONGS); break;
                case "albums": search(a[1], YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS); break;
                case "stream": stream(a[1]); break;
                case "playlist": playlist(a[1]); break;
                default: throw new IllegalArgumentException(a[0]);
            }
        } catch (Throwable t) {
            System.out.println("THROWN: " + t.getClass().getName() + ": " + t.getMessage());
            Throwable c = t.getCause();
            while (c != null) { System.out.println("  cause: " + c.getClass().getName() + ": " + c.getMessage()); c = c.getCause(); }
        }
    }

    static void search(String query, String filter) throws Exception {
        SearchExtractor ex = ServiceList.YouTube.getSearchExtractor(query, List.of(filter), "");
        ex.fetchPage();
        List<InfoItem> items = ex.getInitialPage().getItems();
        System.out.println("items: " + items.size());
        int i = 0;
        for (InfoItem it : items) {
            if (it instanceof StreamInfoItem s) {
                System.out.printf("#%d song '%s' / '%s' / %ds / %s%n", i, s.getName(), s.getUploaderName(), s.getDuration(), s.getUrl());
            } else if (it instanceof PlaylistInfoItem p) {
                System.out.printf("#%d album '%s' / '%s' / count=%d / %s%n", i, p.getName(), p.getUploaderName(), p.getStreamCount(), p.getUrl());
            } else {
                System.out.printf("#%d %s '%s'%n", i, it.getClass().getSimpleName(), it.getName());
            }
            i++;
        }
    }

    static void stream(String id) throws Exception {
        StreamExtractor ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=" + id);
        ex.fetchPage();
        System.out.printf("name='%s' uploader='%s' length=%d%n", ex.getName(), ex.getUploaderName(), ex.getLength());
        for (AudioStream a : ex.getAudioStreams()) {
            String u = a.getContent();
            System.out.printf("  itag=%d fmt=%s avg=%d codec=%s vision=%s url=%s%n", a.getItag(), a.getFormat(), a.getAverageBitrate(), a.getCodec(),
                    YoutubeParsingHelper.isVisionOsStreamingUrl(u), u.length() > 110 ? u.substring(0, 110) + "..." : u);
        }
        System.out.println("UA=" + YoutubeParsingHelper.getVisionOsUserAgent(Localization.DEFAULT));
    }

    static String safe(java.util.concurrent.Callable<Object> c) {
        try { return String.valueOf(c.call()); } catch (Throwable t) { return "!" + t.getClass().getSimpleName() + ":" + t.getMessage(); }
    }

    static void playlist(String id) throws Exception {
        PlaylistExtractor ex = ServiceList.YouTube.getPlaylistExtractor("https://www.youtube.com/playlist?list=" + id);
        ex.fetchPage();
        System.out.printf("class=%s name='%s' streamCount=%s%n", ex.getClass().getSimpleName(), safe(ex::getName), safe(ex::getStreamCount));
        List<StreamInfoItem> items = ex.getInitialPage().getItems();
        int i = 0;
        for (StreamInfoItem s : items) {
            System.out.printf("  #%d '%s' / '%s' / %ds / %s%n", i++, s.getName(), s.getUploaderName(), s.getDuration(), s.getUrl());
        }
        System.out.println("hasNextPage=" + ex.getInitialPage().hasNextPage());
    }
}

package bot;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Prepara o yt-dlp (e o Deno, que ele usa para o YouTube).
 * No seu PC usa os que você instalou; em um servidor Linux (Discloud) baixa sozinho.
 */
final class Tools {

    private static final Path DIR = Path.of("tools");
    private static final String YTDLP_URL =
            "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux";
    private static final String DENO_URL =
            "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-unknown-linux-gnu.zip";

    private static String ytdlpCommand = "yt-dlp";
    private static boolean localTools = false;
    private static boolean done = false;

    private Tools() {}

    /** Começa a preparar em segundo plano, para o bot ligar rápido. */
    static void startInBackground() {
        Thread t = new Thread(Tools::ensure, "tools-setup");
        t.setDaemon(true);
        t.start();
    }

    static synchronized void ensure() {
        if (done) return;
        done = true;
        try {
            if (works("yt-dlp")) {
                System.out.println("[tools] yt-dlp encontrado no sistema.");
                return;
            }
            if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
                System.err.println("[tools] yt-dlp não encontrado. Instale com: winget install yt-dlp.yt-dlp");
                return;
            }

            Files.createDirectories(DIR);

            Path ytdlp = DIR.resolve("yt-dlp");
            if (isStale(ytdlp)) {
                System.out.println("[tools] baixando yt-dlp...");
                download(YTDLP_URL, ytdlp);
                ytdlp.toFile().setExecutable(true);
            }

            Path deno = DIR.resolve("deno");
            if (isStale(deno)) {
                System.out.println("[tools] baixando deno...");
                Path zip = DIR.resolve("deno.zip");
                download(DENO_URL, zip);
                unzipFile(zip, "deno", deno);
                deno.toFile().setExecutable(true);
                Files.deleteIfExists(zip);
            }

            ytdlpCommand = ytdlp.toAbsolutePath().toString();
            localTools = true;
            System.out.println("[tools] yt-dlp e deno prontos.");
        } catch (Exception e) {
            System.err.println("[tools] falha ao preparar o yt-dlp: " + e);
        }
    }

    /** Cria o comando do yt-dlp já com os argumentos. */
    static ProcessBuilder process(String... args) {
        ensure();
        List<String> cmd = new ArrayList<>();
        cmd.add(ytdlpCommand);
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (localTools) {
            // para o yt-dlp achar o deno
            String path = System.getenv().getOrDefault("PATH", "");
            pb.environment().put("PATH", DIR.toAbsolutePath() + File.pathSeparator + path);
        }
        return pb;
    }

    private static boolean works(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Baixa de novo se não existir ou se tiver mais de 24 horas. */
    private static boolean isStale(Path file) throws Exception {
        if (!Files.isExecutable(file)) return true;
        Instant modified = Files.getLastModifiedTime(file).toInstant();
        return modified.isBefore(Instant.now().minus(Duration.ofHours(24)));
    }

    private static void download(String url, Path target) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("User-Agent", "psyco-dj-bot")
                .build();

        Path tmp = Files.createTempFile(DIR, "download", ".tmp");
        try {
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() != 200) {
                throw new Exception("download falhou (HTTP " + response.statusCode() + "): " + url);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void unzipFile(Path zip, String entryName, Path target) throws Exception {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new Exception("'" + entryName + "' não encontrado dentro de " + zip);
    }
}

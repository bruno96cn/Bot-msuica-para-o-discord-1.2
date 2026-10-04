package bot;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        String token = loadToken();
        if (token == null || token.isBlank()) {
            System.err.println("Defina a variável de ambiente DISCORD_TOKEN com o token do bot (ou use um arquivo .env).");
            printDiagnostics();
            return;
        }

        Tools.startInBackground(); // prepara o yt-dlp sem atrasar o bot

        JDA jda = JDABuilder.createDefault(token,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,   // precisa estar ligada no Developer Portal
                        GatewayIntent.GUILD_VOICE_STATES)
                .setAudioModuleConfig(new AudioModuleConfig()
                        .withDaveSessionFactory(new JDaveSessionFactory()))
                .setActivity(Activity.listening("!ajuda"))
                .addEventListeners(new MusicListener())
                .build();

        jda.awaitReady();
        System.out.println(">>> Bot: " + jda.getSelfUser().getName());
        System.out.println(">>> Servidores em que o bot está: " + jda.getGuilds().size());
        jda.getGuilds().forEach(g -> System.out.println(">>> - " + g.getName()));
    }

    /** Pega o token da variável de ambiente; se não existir, lê do arquivo .env. */
    private static String loadToken() {
        String token = System.getenv("DISCORD_TOKEN");
        if (token != null && !token.isBlank()) {
            return token.trim();
        }

        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of(".env"));
        try {
            Path jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
            if (dir != null) candidates.add(dir.resolve(".env"));
        } catch (Exception ignored) {
            // sem problema: só não procura ao lado do .jar
        }

        for (Path file : candidates) {
            if (!Files.isRegularFile(file)) continue;
            try {
                String value = readValue(file, "DISCORD_TOKEN");
                if (value != null && !value.isBlank()) {
                    System.out.println("Token lido do arquivo " + file.toAbsolutePath());
                    return value;
                }
            } catch (IOException e) {
                System.err.println("Não consegui ler " + file + ": " + e.getMessage());
            }
        }
        return null;
    }

    private static String readValue(Path file, String wantedKey) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        Charset charset = StandardCharsets.UTF_8;
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;   // arquivo salvo pelo PowerShell
        } else if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
        }
        String text = new String(bytes, charset).replace("\uFEFF", "");

        for (String line : text.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            if (key.startsWith("export ")) key = key.substring(7).trim();
            if (!key.equals(wantedKey)) continue;

            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            return value;
        }
        return null;
    }

    /** Ajuda a descobrir onde o .env deveria estar (não mostra o token). */
    private static void printDiagnostics() {
        System.err.println("Pasta atual: " + Path.of("").toAbsolutePath());
        try (Stream<Path> files = Files.list(Path.of(""))) {
            System.err.println("Arquivos nessa pasta:");
            files.forEach(p -> System.err.println(" - " + p.getFileName()));
        } catch (IOException e) {
            System.err.println("Não consegui listar a pasta: " + e.getMessage());
        }
    }
}

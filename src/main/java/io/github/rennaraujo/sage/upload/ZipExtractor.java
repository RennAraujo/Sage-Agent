package io.github.rennaraujo.sage.upload;

import io.github.rennaraujo.sage.document.InvalidDocumentException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lê um zip em streaming, sem nunca gravar em disco. Conta os bytes descompactados enquanto lê (o tamanho declarado
 * no cabeçalho da entrada é controlado por quem fez o zip e não serve de limite), recusa caminhos absolutos, com
 * {@code ..} ou com barra invertida, ignora diretórios e lixo de sistema (pastas {@code __MACOSX}, arquivos ocultos)
 * e só aceita entradas {@code .md} e {@code .txt}.
 */
public final class ZipExtractor {

    /** Uma entrada aceita: o caminho dentro do zip e o conteúdo bruto. */
    public record Entry(String path, byte[] content) {}

    private static final int BUFFER_BYTES = 8192;
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:.*");
    private static final String MACOS_RESOURCE_DIR = "__MACOSX";
    private static final List<String> ACCEPTED_EXTENSIONS = List.of(".md", ".txt");

    private final UploadProperties properties;

    public ZipExtractor(UploadProperties properties) {
        this.properties = properties;
    }

    public List<Entry> extract(byte[] zip) {
        long maxEntryBytes = properties.maxFileBytes().toBytes();
        long maxTotalBytes = properties.maxZipUncompressedBytes().toBytes();
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry zipEntry;
            while ((zipEntry = in.getNextEntry()) != null) {
                count++;
                if (count > properties.maxZipEntries()) {
                    throw new UploadTooLargeException("Zip com entradas demais");
                }
                String name = zipEntry.getName();
                requireSafePath(name);
                boolean skip = zipEntry.isDirectory() || isSystemJunk(name);
                if (!skip) {
                    requireAcceptedExtension(name);
                }
                byte[] content = read(in, maxEntryBytes, maxTotalBytes - total);
                total += content.length;
                if (!skip) {
                    entries.add(new Entry(name, content));
                }
            }
        } catch (IOException e) {
            // A mensagem do JDK pode citar nomes de entrada; não repassamos nem encadeamos.
            throw new InvalidDocumentException("Zip inválido ou corrompido");
        }
        if (count == 0) {
            // Bytes que nem começam como zip também terminam aqui, sem erro do JDK: zero entradas lidas.
            throw new InvalidDocumentException("Zip inválido ou vazio");
        }
        return List.copyOf(entries);
    }

    /** Lê a entrada atual contando os bytes; aborta assim que passa do limite da entrada ou do que resta no total. */
    private static byte[] read(InputStream in, long maxEntryBytes, long remainingTotal) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        long entryBytes = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            entryBytes += n;
            if (entryBytes > remainingTotal) {
                throw new UploadTooLargeException("Conteúdo descompactado do zip acima do limite");
            }
            if (entryBytes > maxEntryBytes) {
                throw new UploadTooLargeException("Entrada do zip acima do limite de tamanho");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void requireSafePath(String name) {
        boolean unsafe = name.isEmpty()
                || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0
                || name.startsWith("/")
                || DRIVE_PREFIX.matcher(name).matches();
        for (String segment : name.split("/", -1)) {
            unsafe |= segment.equals("..");
        }
        if (unsafe) {
            throw new InvalidDocumentException(
                    "Zip com entrada de caminho inválido (absoluto, com .. ou barra invertida)");
        }
    }

    private static boolean isSystemJunk(String name) {
        for (String segment : name.split("/")) {
            if (segment.equals(MACOS_RESOURCE_DIR) || segment.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static void requireAcceptedExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (ACCEPTED_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
            throw new UnsupportedDocumentException("O zip só pode conter arquivos .md e .txt");
        }
    }
}

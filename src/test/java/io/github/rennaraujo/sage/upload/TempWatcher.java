package io.github.rennaraujo.sage.upload;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Registra o nome de todo arquivo criado num diretório enquanto aberto, mesmo que seja apagado logo depois. */
final class TempWatcher implements AutoCloseable {

    private static final long SETTLE_MILLIS = 500;

    private final WatchService service;
    private final List<String> created = new CopyOnWriteArrayList<>();
    private final Thread thread;

    TempWatcher(Path directory) throws IOException {
        service = FileSystems.getDefault().newWatchService();
        directory.register(service, StandardWatchEventKinds.ENTRY_CREATE);
        thread = new Thread(this::loop, "temp-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        try {
            while (true) {
                WatchKey key = service.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    created.add(String.valueOf(event.context()));
                }
                key.reset();
            }
        } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Nomes criados até agora, depois de uma pequena espera para os eventos do sistema de arquivos chegarem. */
    List<String> created() {
        try {
            Thread.sleep(SETTLE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return List.copyOf(created);
    }

    @Override
    public void close() throws IOException {
        service.close();
    }
}

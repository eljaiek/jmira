package org.eljaiek.jmira.app.controller.util;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.concurrent.Task;
import javafx.event.EventHandler;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import org.controlsfx.control.TaskProgressView;
import org.eljaiek.jmira.app.util.FileSystemHelper;
import org.eljaiek.jmira.core.*;
import org.eljaiek.jmira.data.model.DebPackage;
import org.eljaiek.jmira.data.repositories.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Created by eduardo.eljaiek on 12/4/2015.
 */
public final class DownloadScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(DownloadScheduler.class);

    private static final String APPENDER_NAME = "AREA";

    private volatile boolean stopDownloads;

    private volatile int remainingThreads;

    private final SplitPane container;

    private final TaskProgressView<Task<Void>> progressView;

    private final LogViewer logViewer;

    private final IntegerProperty downloadedCount;

    private final LongProperty downloaded;

    private final Queue<Task<Void>> tasks;

    private final PackageService packageService;

    private Queue<DebPackage> queue;

    private ExecutorService pool;

    private EventHandler<DownloadEvent> onDone;

    private EventHandler<DownloadEvent> onCancelled;

    private EventHandler<DownloadEvent> onFail;

    private EventHandler<DownloadEvent> onLoadSucceeded;

    public DownloadScheduler(PackageService packageService) {
        this.packageService = packageService;
        downloadedCount = new SimpleIntegerProperty(0);
        downloaded = new SimpleLongProperty(0);
        queue = new ConcurrentLinkedQueue<>();
        tasks = new ConcurrentLinkedQueue<>();
        progressView = new TaskProgressView<>();
        logViewer = new LogViewer();
        container = new SplitPane(progressView, logViewer);
        container.setOrientation(Orientation.VERTICAL);
    }

    public Node getControl() {
        return container;
    }

    public IntegerProperty downloadedCountProperty() { return downloadedCount; }

    public LongProperty downloadedProperty() {
        return downloaded;
    }

    public final void cancel() {
        stopDownloads = true;
        tasks.forEach(task -> task.cancel(true));
        pool.shutdown();
        fireCancelledEvent();
    }

    public final void start() {
        LogbackAppenderAdapter.register(APPENDER_NAME, logViewer);
        pool = Executors.newWorkStealingPool();
        Task<Void> task = new SearchTask();

        task.setOnSucceeded(evt -> {
            fireLoadSucceeded();
            int processors = Runtime.getRuntime().availableProcessors();
            List<DownloadModel> downloads = createDownloads(processors);

            if (downloads.isEmpty()) {
                fireDoneEvent();
            }

            remainingThreads = downloads.size();
            downloads.forEach(this::start);
        });

        task.setOnFailed(evt -> fireFailEvent(
                new DownloadException(MessageResolver.getDefault()
                        .getMessage("download.process.fail", task.getException().getMessage()), task.getException())));

        start(task);
    }

    private void start(DownloadModel download) {
        DownloadTask task = new DownloadTask(download);
        download.register(task);

        task.setOnSucceeded(evt -> {

            if (stopDownloads) {
                return;
            }

            if (queue.isEmpty()) {
                remainingThreads--;

                if (remainingThreads == 0) {
                    fireDoneEvent();
                }

                return;
            }

            List<DownloadModel> pack = createDownloads(1);

            if (pack.size() > 0) {
                start(pack.iterator().next());
            }
        });

        start(task);
    }

    private void start(Task<Void> task) {

        try {
            tasks.add(task);
            progressView.getTasks().add(task);
            pool.submit(task);
        } catch (RejectedExecutionException ex) {
            LOG.error(ex.getMessage(), ex);
        }
    }

    private List<DownloadModel> createDownloads(int quantity) {
        int i = 0;
        List<DownloadModel> downloads = new ArrayList<>(quantity);

        while (i < quantity && i < downloads.size()) {
            DebPackage p = queue.poll();
            String localUrl = p.getLocalUrl();
            String folder = localUrl.substring(0, localUrl.lastIndexOf('/'));
            Download download = DownloadBuilder
                    .create()
                    .localFolder(folder).url(p.getRemoteUrl())
                    .get();
            downloads.add(new DownloadModel(p.getName(), p.getSize(), download));
            i++;
        }

        return downloads;
    }

    private void fireDoneEvent() {
        reset();

        if (onDone != null) {
            onDone.handle(new DownloadEvent(DownloadEvent.DOWN_DONE));
        }
    }

    private void fireFailEvent(Exception error) {
        reset();

        if (onFail != null) {
            onFail.handle(new DownloadFailEvent(DownloadFailEvent.DOWN_FAIL, error));
        }
    }

    private void fireLoadSucceeded() {

        if (onLoadSucceeded != null) {
            onLoadSucceeded.handle(new DownloadEvent(DownloadEvent.DOWN_SEARCH_DONE));
        }
    }

    private void fireCancelledEvent() {
         reset();

         if (onCancelled != null) {
             onCancelled.handle(new DownloadEvent(DownloadEvent.DOWN_CANCELLED));
         }

    }

    private void reset() {
        LogbackAppenderAdapter.remove(APPENDER_NAME);
        logViewer.replaceText(0, logViewer.getText().length(), "");
        progressView.getTasks().clear();
        stopDownloads = false;
        tasks.clear();
        queue.clear();
    }

    public void setOnLoadSucceeded(EventHandler<DownloadEvent> onLoadSucceeded) {
        this.onLoadSucceeded = onLoadSucceeded;
    }

    public void setOnCancelled(EventHandler onCancelled) {
        this.onCancelled = onCancelled;
    }

    public void setOnDone(EventHandler onDone) {
        this.onDone = onDone;
    }

    public void setOnFail(EventHandler<DownloadEvent> onFail) {
        this.onFail = onFail;
    }

    class SearchTask extends Task<Void> {

        @Override
        protected Void call() throws Exception {

            try {
                updateTitle(MessageResolver.getDefault().getMessage("search.task.title"));
                queue = new ConcurrentLinkedQueue<>(packageService.listNotDownloaded());
                return null;
            } catch (DataAccessException ex) {
                LOG.error(ex.getMessage(), ex.getMessage());
                throw new RuntimeException(MessageResolver.getDefault().getMessage("search.process.fail"));
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }

    class DownloadTask extends Task<Void> implements Observer {

        private final DownloadModel download;

        public DownloadTask(DownloadModel download) {
            this.download = download;
            updateTitle(download.getPackageName());
            progressView.setGraphicFactory(null);
        }

        @Override
        protected Void call() throws Exception {

            try {
                download.run();
            } catch (DownloadException ex) {
                LOG.error(MessageResolver
                        .getDefault()
                        .getMessage("download.task.fail",
                                download.getPackageName(), ex.getMessage()));
            }

            return null;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {

            if (stopDownloads) {
                download.cancel();
                return super.cancel(mayInterruptIfRunning);
            }

            return false;
        }

        @Override
        public void update(Observable o, Object arg) {
            Download dn = (Download) o;

            if (DownloadStatus.DOWNLOADING == dn.getStatus()) {
                updateProgress(dn.getDownloaded(), dn.getSize());
                String formattedProgress = FileSystemHelper.formatSize(download.getDownloaded());
                String formattedSize = FileSystemHelper.formatSize(download.getSize());
                updateMessage(MessageResolver.getDefault()
                        .getMessage("download.task.info", formattedProgress, formattedSize));
                return;
            }

            if (DownloadStatus.COMPLETE == dn.getStatus()) {
                downloadedCount.set(downloadedCount.get() + 1);
                downloaded.set(downloaded.get() + dn.getSize());
                LOG.info(MessageResolver
                        .getDefault()
                        .getMessage("download.task.done", download.getPackageName()));
            }
        }
    }
}
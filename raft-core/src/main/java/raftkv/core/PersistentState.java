package raftkv.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//how state is saved to disk
public class PersistentState {
    private static final Logger logger = LoggerFactory.getLogger(PersistentState.class);

    private final File metadataFile;//just contains 2 things: term & votedFor
    private final File logFile;//contains log entries

    private long currentTerm = 0;
    private int votedFor = -1;
    private final List<LogEntry> log = new ArrayList<>();//again this is just used as a cache for the Apis this class exposes, so hat we dont have to read from disk again and again

    public PersistentState(String dataDir) {
        File dir = new File(dataDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.metadataFile = new File(dir, "metadata.state");
        this.logFile = new File(dir, "raft.log");
        load();
    }

    public synchronized long getCurrentTerm() {
        return currentTerm;
    }

    public synchronized int getVotedFor() {
        return votedFor;
    }

    public synchronized List<LogEntry> getLog() {
        return new ArrayList<>(log);
    }

    public synchronized void saveMetadata(long term, int votedFor) {
        //1> save in memory
        this.currentTerm = term;
        this.votedFor = votedFor;
        //2> save in disk
        // We write to a temporary file first. If a crash happens mid write, only the temp file is corrupted; the original metadata.state remains
        //  perfectly safe and intact.
        File tempFile = new File(metadataFile.getParentFile(), "metadata.state.tmp");
        try (PrintWriter writer = new PrintWriter(new FileWriter(tempFile))) {
            writer.println("term=" + term);
            writer.println("votedFor=" + votedFor);
            writer.flush();
            Files.move(tempFile.toPath(), metadataFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);// Once the temp file is fully written and closed, we tell the operating system to swap/rename metadata.state.tmp to metadata.state.Either the swap succeeds 100%, or nothing happens at all (if power cuts out during the move). There is no in-between state where the destination file is left partially overwritten or corrupted. This pattern is the exact same pattern used by production databases (like SQLite or PostgreSQL) to guarantee that they never wake up with corrupted configuration files after a sudden crash!
        } catch (IOException e) {
            logger.error("Failed to persist metadata state", e);
        }
    }

    public synchronized void appendEntry(LogEntry entry) {
        log.add(entry);
        try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
            String base64Command = Base64.getEncoder().encodeToString(entry.command().getBytes(StandardCharsets.UTF_8));
            writer.println(entry.term() + ":" + entry.index() + ":" + base64Command);
        } catch (IOException e) {
            logger.error("Failed to append log entry to file", e);
        }
    }

    public synchronized void truncateLog(long fromIndex) {
        if (fromIndex <= 0) return;
        
        while (log.size() > 0 && log.get(log.size() - 1).index() >= fromIndex) {
            log.remove(log.size() - 1);
        }
        
        File tempFile = new File(logFile.getParentFile(), "raft.log.tmp");
        try (PrintWriter writer = new PrintWriter(new FileWriter(tempFile))) {
            for (LogEntry entry : log) {
                String base64Command = Base64.getEncoder().encodeToString(entry.command().getBytes(StandardCharsets.UTF_8));
                writer.println(entry.term() + ":" + entry.index() + ":" + base64Command);
            }
            writer.flush();
            Files.move(tempFile.toPath(), logFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.error("Failed to truncate and rewrite log file", e);
        }
    }

    private void load() {
        if (metadataFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(metadataFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("term=")) {
                        currentTerm = Long.parseLong(line.substring(5).trim());
                    } else if (line.startsWith("votedFor=")) {
                        votedFor = Integer.parseInt(line.substring(9).trim());
                    }
                }
            } catch (Exception e) {
                logger.error("Error loading metadata state, starting fresh", e);
            }
        }

        log.clear();
        if (logFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":", 3);
                    if (parts.length == 3) {
                        long term = Long.parseLong(parts[0]);
                        long index = Long.parseLong(parts[1]);
                        String command = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
                        log.add(new LogEntry(term, index, command));
                    }
                }
            } catch (Exception e) {
                logger.error("Error loading log entries, starting fresh", e);
            }
        }
    }
}

package raftkv.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
//manager class for Logs
public class RaftLog {

    /*


    why do we maintain in memory lists and data structures here? wasn't just PersistenceState class enough??


    - coz servers send acknowledgements, votes, heartbeats to other servers, reading from Log file, if we plainly read from Disk(ie using PersistentState class only), it wud really be slow than reading from in memory, coz there are many reads, so v maintain it



     */

    private final List<LogEntry> entries = new ArrayList<>();
    private final PersistentState persistentState;

    public RaftLog() {
        this(null);
    }

    public RaftLog(PersistentState persistentState) {
        this.persistentState = persistentState;
        // Index 0 is a dummy entry to make the log 1-indexed.
        entries.add(new LogEntry(0, 0, ""));
    }

    public synchronized long getLastLogIndex() {
        return entries.size() - 1;
    }

    public synchronized long getLastLogTerm() {
        return entries.get(entries.size() - 1).term();
    }

    public synchronized long getTermAt(long index) {
        if (index < 0 || index >= entries.size()) {
            return 0;
        }
        return entries.get((int) index).term();
    }

    public synchronized LogEntry getEntryAt(long index) {
        if (index <= 0 || index >= entries.size()) {
            return null;
        }
        return entries.get((int) index);
    }

    public synchronized boolean hasEntryAt(long index, long term) {
        if (index < 0 || index >= entries.size()) {
            return false;
        }
        return entries.get((int) index).term() == term;
    }

    public synchronized List<LogEntry> getEntriesFrom(long index) {
        if (index <= 0 || index >= entries.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(entries.subList((int) index, entries.size()));
    }

    public synchronized long append(long term, String command) {
        long nextIndex = entries.size();
        LogEntry entry = new LogEntry(term, nextIndex, command);
        entries.add(entry);
        if (persistentState != null) {
            persistentState.appendEntry(entry);
        }
        return nextIndex;
    }

    public synchronized void append(LogEntry entry) {
        if (entry.index() == entries.size()) {
            entries.add(entry);
            if (persistentState != null) {
                persistentState.appendEntry(entry);
            }
        } else if (entry.index() < entries.size()) {
            //when it can happen:  If a leader started writing entries but crashed before committing them, those entries are invalid. A new leader is now sending us the correct entries for those same indexes.
            LogEntry existing = entries.get((int) entry.index());
            if (existing.term() != entry.term()) {
                truncate(entry.index());
                entries.add(entry);
                if (persistentState != null) {
                    persistentState.appendEntry(entry);
                }
            }//else it's just a duplicate network command, so ignore it
        } else {
            throw new IllegalStateException("Cannot append entry with index " + entry.index() + " to log of size " + entries.size());
        }
    }

    public synchronized void truncate(long fromIndex) {
        if (fromIndex <= 0 || fromIndex >= entries.size()) {
            return;
        }
        while (entries.size() > fromIndex) {
            entries.remove(entries.size() - 1);
        }
        if (persistentState != null) {
            persistentState.truncateLog(fromIndex);
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void reset(List<LogEntry> newEntries) {
        entries.clear();
        entries.add(new LogEntry(0, 0, ""));
        for (LogEntry entry : newEntries) {
            if (entry.index() > 0) {
                entries.add(entry);
            }
        }
    }

    @Override
    public synchronized String toString() {
        return "RaftLog{" +
                "size=" + (entries.size() - 1) +
                ", lastIndex=" + getLastLogIndex() +
                ", lastTerm=" + getLastLogTerm() +
                '}';
    }
}

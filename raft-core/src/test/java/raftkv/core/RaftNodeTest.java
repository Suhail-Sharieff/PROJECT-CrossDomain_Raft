package raftkv.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

public class RaftNodeTest {
    private ScheduledExecutorService scheduler;

    @BeforeEach
    public void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        scheduler.shutdownNow();
        scheduler.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    public void testSingleNodeElection() {
        // A single-node cluster should become leader immediately on startup/timeout
        AtomicReference<NodeState> lastState = new AtomicReference<>(NodeState.FOLLOWER);
        
        RaftNodeListener listener = new RaftNodeListener() {
            @Override
            public void sendRequestVote(int peerId, long term, int candidateId, long lastLogIndex, long lastLogTerm) {}

            @Override
            public void sendAppendEntries(int peerId, long term, int leaderId, long prevLogIndex, long prevLogTerm, List<LogEntry> entries, long leaderCommit) {}

            @Override
            public void onCommit(long logIndex, LogEntry entry) {}

            @Override
            public void onStateChange(NodeState oldState, NodeState newState, long term) {
                lastState.set(newState);
            }
        };

        RaftNode node = new RaftNode(1, Collections.emptyList(), scheduler, listener, 50, 100, 20);
        node.start();

        await().atMost(1, TimeUnit.SECONDS).until(() -> node.getRole() == NodeState.LEADER);

        assertEquals(NodeState.LEADER, node.getRole());
        assertEquals(1, node.getCurrentTerm());
        assertEquals(1, node.getVotedFor());
        
        node.stop();
    }
}

package ch.uzh.ifi.hase.soprafs26.service;

import org.slf4j.Logger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs non-database side effects only after a surrounding transaction commits. */
final class PostCommitActionExecutor {

    private PostCommitActionExecutor() {
    }

    static void execute(Logger log, String description, Runnable action) {
        Runnable safeAction = () -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                log.error("Post-commit action failed: {}", description, ex);
            }
        };

        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeAction.run();
                }
            });
            return;
        }

        safeAction.run();
    }
}

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class Main {
    static final long INITIAL = 100_000;
    // 100,000 start + 15,000 deposit - 20,000 (A) - 25,000 (D) - 10,000 (E purchase); C's transfer stays in the bank
    static final long EXPECTED_TOTAL = INITIAL + 15_000 - 20_000 - 25_000 - 10_000;

    // ---------- helpers (avoid repeating thread/printing boilerplate) ----------
    /** Starts every task at the same instant, then waits for all to finish. */
    static void runConcurrently(List<Thread> threads, CountDownLatch gate) throws InterruptedException {
        threads.forEach(Thread::start);
        gate.countDown();                     // release all threads together
        for (Thread t : threads) t.join();
    }

    static Thread worker(String name, CountDownLatch gate, Runnable task) {
        return new Thread(() -> {
            try { gate.await(); task.run(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, name);
    }

    static void banner(String text) { System.out.println("\n=== " + text + " ==="); }

    // ---------- Scenario 1 & 2: the six customers from the question ----------
    static boolean runBankDay(String title, BiFunction<String, Long, BankAccount> factory) throws InterruptedException {
        banner(title);
        BankAccount main = factory.apply("MAIN-ACC", INITIAL);
        BankAccount other = factory.apply("OTHER-ACC", 0L);     // destination of Customer C's transfer
        CountDownLatch gate = new CountDownLatch(1);

        List<Thread> threads = new ArrayList<>(List.of(
                worker("Customer-A", gate, () -> main.withdraw(20_000)),
                worker("Customer-B", gate, () -> main.deposit(15_000)),
                worker("Customer-C", gate, () -> main.transferTo(other, 30_000)),
                worker("Customer-D", gate, () -> main.withdraw(25_000)),
                worker("Customer-E", gate, () -> main.purchase(10_000)),
                worker("Customer-F", gate, () -> System.out.printf("  [Customer-F] balance seen: KSh %,d%n", main.getBalance()))));
        runConcurrently(threads, gate);

        boolean ok = main.audit() & other.audit();
        long actualTotal = main.rawBalance() + other.rawBalance();
        boolean totalOk = actualTotal == EXPECTED_TOTAL;
        System.out.printf("  Money in both accounts: actual KSh %,d, expected KSh %,d -> %s%n",
                actualTotal, EXPECTED_TOTAL, totalOk ? "NO MONEY LOST OR CREATED" : "*** MONEY LOST/CREATED ***");
        ok &= totalOk;
        return ok;
    }

    // ---------- Scenario 3: overdraft protection + thread coordination ----------
    static void runCoordinationDemo() throws InterruptedException {
        banner("SCENARIO 3: ReentrantLock + Condition (overdraft protection & waiting for funds)");
        LockedAccount acc = new LockedAccount("WAIT-ACC", 10_000);
        CountDownLatch gate = new CountDownLatch(1);

        List<Thread> threads = List.of(
                worker("Waiter-25k", gate, () -> {           // waits for a deposit, then succeeds
                    try { acc.withdrawWhenFunded(25_000, 3_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }),
                worker("Waiter-100k", gate, () -> {          // deposit never big enough -> times out, rejected
                    try { acc.withdrawWhenFunded(100_000, 500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }),
                worker("Instant-50k", gate, () -> acc.withdraw(50_000)),   // rejected immediately (no overdraft)
                worker("Depositor", gate, () -> {
                    try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    acc.deposit(20_000);
                }));
        runConcurrently(threads, gate);
        acc.audit();
    }

    public static void main(String[] args) throws InterruptedException {
        boolean unsafeOk = runBankDay("SCENARIO 1: NO synchronization (race condition expected)", UnsafeAccount::new);
        boolean syncOk   = runBankDay("SCENARIO 2a: synchronized block", SynchronizedAccount::new);
        boolean lockOk   = runBankDay("SCENARIO 2b: ReentrantLock", LockedAccount::new);
        runCoordinationDemo();

        banner("SUMMARY");
        System.out.println("  Unsafe       : " + (unsafeOk ? "happened to be consistent (rare)" : "RACE CONDITION - balance wrong"));
        System.out.println("  synchronized : " + (syncOk ? "consistent" : "FAILED"));
        System.out.println("  ReentrantLock: " + (lockOk ? "consistent" : "FAILED"));
    }
}

/** Kind of transaction. sign: +1 credits the account, -1 debits it, 0 leaves it unchanged. */
enum Type {
    DEPOSIT(+1), TRANSFER_IN(+1),
    WITHDRAWAL(-1), PURCHASE(-1), TRANSFER_OUT(-1),
    BALANCE_INQUIRY(0);

    final int sign;
    Type(int sign) { this.sign = sign; }
    boolean isDebit() { return sign < 0; }
}

/** One audit-trail entry. */
record Transaction(long id, String account, Type type, long amount,
                   boolean success, long balanceAfter, String thread) {
    @Override public String toString() {
        return String.format("#%-3d %-12s %-15s KSh %,8d  %-8s balance=%,9d  [%s]",
                id, account, type, amount, success ? "OK" : "REJECTED", balanceAfter, thread);
    }
}

/**
 * All banking rules live here ONCE. Subclasses only decide HOW a block of code is
 * protected (nothing, synchronized, or ReentrantLock) by implementing guarded().
 */
abstract class BankAccount {
    private static final AtomicLong TXN_SEQ = new AtomicLong();   // unique transaction ids
    private static final long WORK_MS = 20;                       // simulated processing time

    private final String id;
    private final long openingBalance;
    private long balance;                                         // the shared mutable state
    private final Queue<Transaction> ledger = new ConcurrentLinkedQueue<>(); // thread-safe audit log

    protected BankAccount(String id, long openingBalance) {
        this.id = id;
        this.openingBalance = openingBalance;
        this.balance = openingBalance;
    }

    /** Runs the action under this account's concurrency strategy. */
    protected abstract <T> T guarded(Supplier<T> action);

    // ---------- public API ----------
    public boolean deposit(long amount)  { return apply(Type.DEPOSIT, amount); }
    public boolean withdraw(long amount) { return apply(Type.WITHDRAWAL, amount); }
    public boolean purchase(long amount) { return apply(Type.PURCHASE, amount); }

    /** Debit this account, then credit the destination. Never holds two locks => no deadlock. */
    public boolean transferTo(BankAccount destination, long amount) {
        if (destination == this) throw new IllegalArgumentException("Cannot transfer to same account");
        if (!apply(Type.TRANSFER_OUT, amount)) return false;   // rejected: nothing moved
        destination.apply(Type.TRANSFER_IN, amount);           // credits always succeed
        return true;
    }

    public long getBalance() {
        return guarded(() -> {
            record(Type.BALANCE_INQUIRY, 0, true, balance);
            return balance;
        });
    }

    // ---------- the single place where the balance changes ----------
    private boolean apply(Type type, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        return guarded(() -> {
            long current = balance;                              // 1. read
            simulateProcessing();                                // widens the race window
            boolean ok = !type.isDebit() || current >= amount;   // 2. overdraft check
            long updated = ok ? current + type.sign * amount : current;
            balance = updated;                                   // 3. write
            record(type, amount, ok, updated);
            return ok;
        });
    }

    private void record(Type type, long amount, boolean ok, long balanceAfter) {
        ledger.add(new Transaction(TXN_SEQ.incrementAndGet(), id, type, amount, ok,
                balanceAfter, Thread.currentThread().getName()));
    }

    private static void simulateProcessing() {
        try { Thread.sleep(WORK_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Reads the balance without logging. Use only inside a guarded block or after all threads have joined. */
    public long rawBalance() { return balance; }

    // ---------- audit ----------
    public long expectedBalance() {
        long expected = openingBalance;
        for (Transaction t : ledger) if (t.success()) expected += (long) t.type().sign * t.amount();
        return expected;
    }

    /** Prints the ledger and checks actual balance == opening balance + successful transactions. */
    public boolean audit() {
        System.out.println("  Ledger for " + id + ":");
        ledger.forEach(t -> System.out.println("    " + t));
        long expected = expectedBalance();
        boolean consistent = expected == balance && balance >= 0;
        System.out.printf("  %s: actual=KSh %,d  expected=KSh %,d  -> %s%n",
                id, balance, expected, consistent ? "CONSISTENT" : "*** INCONSISTENT ***");
        return consistent;
    }

    public String getId() { return id; }
}

/** (d) NO protection: demonstrates lost updates and overdrafts. */
class UnsafeAccount extends BankAccount {
    UnsafeAccount(String id, long opening) { super(id, opening); }
    @Override protected <T> T guarded(Supplier<T> action) { return action.get(); }
}

/** (e-1) Intrinsic monitor via a synchronized block. */
class SynchronizedAccount extends BankAccount {
    SynchronizedAccount(String id, long opening) { super(id, opening); }
    @Override protected <T> T guarded(Supplier<T> action) {
        synchronized (this) { return action.get(); }
    }
}

/**
 * (e-2) ReentrantLock + Condition. The Condition provides thread coordination:
 * a withdrawal can WAIT (bounded) for a deposit instead of failing immediately.
 */
class LockedAccount extends BankAccount {
    private final ReentrantLock lock = new ReentrantLock(true);   // fair lock
    private final Condition fundsAvailable = lock.newCondition();

    LockedAccount(String id, long opening) { super(id, opening); }

    @Override protected <T> T guarded(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            fundsAvailable.signalAll();      // balance may have changed: wake waiting withdrawals
            lock.unlock();
        }
    }

    /** Waits up to timeoutMs for enough funds; then attempts the withdrawal (rejected if still short). */
    public boolean withdrawWhenFunded(long amount, long timeoutMs) throws InterruptedException {
        long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        lock.lock();                          // reentrant: withdraw() below re-acquires it safely
        try {
            while (rawBalance() < amount && nanos > 0) nanos = fundsAvailable.awaitNanos(nanos);
            return withdraw(amount);
        } finally {
            lock.unlock();
        }
    }
}
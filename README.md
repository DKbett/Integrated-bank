# Concurrent Commercial Bank Payment Processing Simulation

A Java multithreading project that simulates concurrent payment processing for an e-commerce platform connected to a commercial bank. The program demonstrates how **race conditions** corrupt shared data when multiple threads run without synchronization, and how to fix them using `synchronized` blocks, `ReentrantLock`, and `Condition` objects.

## Problem Scenario

In a real banking system, multiple customers can deposit, withdraw, transfer money, or make online payments on the same account at the exact same time. If these operations are not properly synchronized, threads read stale balances and overwrite each other's updates, causing lost funds or unintended overdrafts.

This program simulates a shared primary account (`MAIN-ACC`) with an initial balance of **KSh 100,000** and a second account (`OTHER-ACC`) starting at **KSh 0**. Six customer threads are launched simultaneously to perform the following transactions:

| Customer Thread | Transaction | Amount (KSh) | Effect on `MAIN-ACC` |
| :--- | :--- | :--- | :--- |
| **Customer-A** | Withdrawal | 20,000 | Deducts 20,000 |
| **Customer-B** | Deposit | 15,000 | Adds 15,000 |
| **Customer-C** | Transfer to `OTHER-ACC` | 30,000 | Deducts 30,000 (credits `OTHER-ACC` 30,000) |
| **Customer-D** | Withdrawal | 25,000 | Deducts 25,000 |
| **Customer-E** | Online Purchase | 10,000 | Deducts 10,000 |
| **Customer-F** | Balance Inquiry | 0 | Reads current balance |

### Expected Balances After All Transactions

* **Expected `MAIN-ACC` Balance:** $\text{KSh } 100,000 + 15,000 - 20,000 - 30,000 - 25,000 - 10,000 = \text{KSh } 30,000$
* **Expected `OTHER-ACC` Balance:** $\text{KSh } 30,000$
* **Total Money in the Bank:** $\text{KSh } 60,000$ (Customer C's transfer stays within the bank, so no money should be lost or created).

## How the Code is Structured

All classes are implemented in `Main.java`. To avoid repeating the banking logic three separate times for each locking approach, the project uses an abstract `BankAccount` base class that handles the core rules while letting subclasses define how synchronization is applied.

### Core Classes and Types

* **`BankAccount` (Abstract Class):**
  * Stores the account `id`, `openingBalance`, current `balance`, and a thread-safe transaction log (`ConcurrentLinkedQueue<Transaction>`).
  * Implements `deposit()`, `withdraw()`, `purchase()`, `transferTo()`, and `getBalance()`.
  * Enforces basic validation: amounts must be positive, an account cannot transfer to itself, and debit transactions fail if `current < amount` (preventing overdrafts).
  * Includes a `20ms` simulated processing delay (`Thread.sleep(20)`) between reading and updating the balance so that race conditions can be observed reliably during unsynchronized runs.
  * Delegates locking to subclasses through the abstract `guarded()` method.
* **`Type` (Enum):**
  * Represents the transaction types (`DEPOSIT`, `TRANSFER_IN`, `WITHDRAWAL`, `PURCHASE`, `TRANSFER_OUT`, `BALANCE_INQUIRY`) along with a `sign` (`+1`, `-1`, or `0`) used to compute balance changes and verify the ledger.
* **`Transaction` (Record):**
  * Records the details of every attempted transaction: a unique sequence number (`AtomicLong`), account ID, transaction type, amount, status (`OK` or `REJECTED`), balance after the transaction, and the thread name.

### Account Subclasses (Concurrency Strategies)

* **`UnsafeAccount`:** Overrides `guarded()` without any locking. Multiple threads enter the critical section at once, resulting in lost updates.
* **`SynchronizedAccount`:** Overrides `guarded()` using a `synchronized (this)` block so only one thread can read or update the account at a time.
* **`LockedAccount`:** Overrides `guarded()` using a fair `ReentrantLock(true)` and a `Condition` (`fundsAvailable`) to support thread coordination.

### Concurrency and Auditing Helpers

* **Simultaneous Thread Release (`CountDownLatch`):** All worker threads wait on a `CountDownLatch(1)` before executing so they start competing for the shared account at the exact same moment.
* **Deadlock-Free Transfers:** In `transferTo()`, the source account is debited inside its lock and released before the destination account is credited under its own lock. Since no thread ever holds two locks at once, circular deadlocks cannot occur.
* **Ledger Audit (`audit()`):** Loops through all successful (`OK`) transactions in the queue, recalculates the expected balance from `openingBalance`, and checks whether it matches the actual final balance.

## Program Scenarios

Running `Main` executes four test scenarios in order:

1. **Scenario 1: No Synchronization (`UnsafeAccount`)**
   * Runs Customers A–F with no locks. Because threads read the old balance during the 20ms processing delay before others finish writing, updates get overwritten and the audit flags the final balance as inconsistent.
2. **Scenario 2a: `synchronized` Block (`SynchronizedAccount`)**
   * Runs the same six customers using Java's built-in intrinsic lock. Threads execute one after another safely, every transaction is logged accurately, and the final balance is consistent (`KSh 30,000` in `MAIN-ACC` and `KSh 30,000` in `OTHER-ACC`).
3. **Scenario 2b: `ReentrantLock` (`LockedAccount`)**
   * Runs the six customers using an explicit `ReentrantLock` with fairness enabled (`true`), ensuring threads acquire the lock in the order they requested it and producing a consistent final balance.
4. **Scenario 3: Overdraft Protection and Thread Coordination (`ReentrantLock` + `Condition`)**
   * Tests `LockedAccount` starting with `KSh 10,000` (`WAIT-ACC`) and four threads:
     * `Instant-50k` tries a standard `withdraw(50,000)` and is rejected immediately because funds are insufficient.
     * `Waiter-25k` calls `withdrawWhenFunded(25,000, 3000)` and waits on `fundsAvailable.awaitNanos()`. After `300ms`, `Depositor` deposits `KSh 20,000` and calls `signalAll()`, waking `Waiter-25k` to complete the withdrawal.
     * `Waiter-100k` waits up to `500ms` for `KSh 100,000`. Since the balance only reaches `KSh 30,000`, it times out and is rejected without overdrawing the account.

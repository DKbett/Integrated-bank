import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

record DbTransaction(
        long id,
        String accountId,
        String type,
        BigDecimal amount,
        boolean success,
        BigDecimal balanceAfter,
        String threadName
) {}

class BankingRepository {
    private static final String URL = "jdbc:mysql://localhost:3306/banking_sim";
    private static final String USER = "root";
    private static final String PASSWORD =
        System.getenv().getOrDefault("DB_PASSWORD", "your_mysql_password");

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    /** Inserts the account row if it does not already exist in MySQL. */
    public void ensureAccount(String accountId, BigDecimal openingBalance) throws SQLException {
        String sql = """
                INSERT IGNORE INTO accounts (account_id, opening_balance, balance)
                VALUES (?, ?, ?)
                """;
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, accountId);
            ps.setBigDecimal(2, openingBalance);
            ps.setBigDecimal(3, openingBalance);
            ps.executeUpdate();
        }
    }

    /** Reads the current balance for an account using a parameterized query. */
    public BigDecimal readBalance(String accountId) throws SQLException {
        String sql = "SELECT balance FROM accounts WHERE account_id = ?";
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getBigDecimal("balance");
                }
                throw new SQLException("Account not found: " + accountId);
            }
        }
    }

    /** Writes an updated balance and inserts an audit row in one atomic DB transaction. */
    public void saveBalanceAndTransaction(
            String accountId,
            BigDecimal newBalance,
            String txnType,
            BigDecimal amount,
            boolean success,
            BigDecimal balanceAfter,
            String threadName
    ) throws SQLException {
        String updateAccountSql = "UPDATE accounts SET balance = ? WHERE account_id = ?";
        String insertTxnSql = """
                INSERT INTO transactions
                    (account_id, txn_type, amount, success, balance_after, thread_name)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection con = getConnection()) {
            con.setAutoCommit(false); // keep balance update and ledger insert together
            try (PreparedStatement updatePs = con.prepareStatement(updateAccountSql);
                 PreparedStatement insertPs = con.prepareStatement(insertTxnSql)) {

                updatePs.setBigDecimal(1, newBalance);
                updatePs.setString(2, accountId);
                updatePs.executeUpdate();

                insertPs.setString(1, accountId);
                insertPs.setString(2, txnType);
                insertPs.setBigDecimal(3, amount);
                insertPs.setBoolean(4, success);
                insertPs.setBigDecimal(5, balanceAfter);
                insertPs.setString(6, threadName);
                insertPs.executeUpdate();

                con.commit();
            } catch (SQLException ex) {
                con.rollback();
                throw ex;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    /** Reads all recorded transactions for an account in order. */
    public List<DbTransaction> readTransactions(String accountId) throws SQLException {
        String sql = """
                SELECT txn_id, account_id, txn_type, amount, success, balance_after, thread_name
                FROM transactions
                WHERE account_id = ?
                ORDER BY txn_id ASC
                """;
        List<DbTransaction> rows = new ArrayList<>();
        try (Connection con = getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new DbTransaction(
                            rs.getLong("txn_id"),
                            rs.getString("account_id"),
                            rs.getString("txn_type"),
                            rs.getBigDecimal("amount"),
                            rs.getBoolean("success"),
                            rs.getBigDecimal("balance_after"),
                            rs.getString("thread_name")
                    ));
                }
            }
        }
        return rows;
    }
}
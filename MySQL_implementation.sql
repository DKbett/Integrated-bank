CREATE DATABASE IF NOT EXISTS banking_sim;
USE banking_sim;

CREATE TABLE accounts (
    account_id      VARCHAR(32)    PRIMARY KEY,
    opening_balance DECIMAL(15, 2) NOT NULL CHECK (opening_balance >= 0),
    balance         DECIMAL(15, 2) NOT NULL CHECK (balance >= 0),
    created_at      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE transactions (
    txn_id        BIGINT         AUTO_INCREMENT PRIMARY KEY,
    account_id    VARCHAR(32)    NOT NULL,
    txn_type      ENUM(
                      'DEPOSIT',
                      'TRANSFER_IN',
                      'WITHDRAWAL',
                      'PURCHASE',
                      'TRANSFER_OUT',
                      'BALANCE_INQUIRY'
                  )              NOT NULL,
    amount        DECIMAL(15, 2) NOT NULL CHECK (amount >= 0),
    success       BOOLEAN        NOT NULL,
    balance_after DECIMAL(15, 2) NOT NULL CHECK (balance_after >= 0),
    thread_name   VARCHAR(100)   NOT NULL,
    created_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_transactions_account
        FOREIGN KEY (account_id) REFERENCES accounts(account_id)
);

-- Seed the accounts used in runBankDay() and runCoordinationDemo()
INSERT INTO accounts (account_id, opening_balance, balance) VALUES
    ('MAIN-ACC',  100000.00, 100000.00),
    ('OTHER-ACC',      0.00,      0.00),
    ('WAIT-ACC',   10000.00,  10000.00)
ON DUPLICATE KEY UPDATE
    balance = VALUES(opening_balance);
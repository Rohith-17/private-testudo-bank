package net.testudobank.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import javax.script.ScriptException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.delegate.DatabaseDelegate;
import org.testcontainers.ext.ScriptUtils;
import org.testcontainers.jdbc.JdbcDatabaseDelegate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import net.testudobank.MvcController;
import net.testudobank.User;
import net.testudobank.helpers.MvcControllerIntegTestHelpers;

@Testcontainers
@SpringBootTest
public class MvcControllerIntegTest {
  //// LITERAL CONSTANTS ////
  private static String CUSTOMER1_ID = "123456789";
  private static String CUSTOMER1_PASSWORD = "password";
  private static String CUSTOMER1_FIRST_NAME = "Foo";
  private static String CUSTOMER1_LAST_NAME = "Bar";
  public static long REASONABLE_TIMESTAMP_EPSILON_IN_SECONDS = 1L;

  // Spins up small MySQL DB in local Docker container
  @Container
  public static MySQLContainer db = new MySQLContainer<>("mysql:5.5")
    .withUsername("root")
    .withPassword("db_password")
    .withDatabaseName("testudo_bank");

  
  private static MvcController controller;
  private static JdbcTemplate jdbcTemplate;
  private static DatabaseDelegate dbDelegate;

  @BeforeAll
  public static void init() throws SQLException {
    dbDelegate = new JdbcDatabaseDelegate(db, "");
    ScriptUtils.runInitScript(dbDelegate, "createDB.sql");
    jdbcTemplate = new JdbcTemplate(MvcControllerIntegTestHelpers.dataSource(db));
    jdbcTemplate.getDataSource().getConnection().setCatalog(db.getDatabaseName());
    controller = new MvcController(jdbcTemplate);
  }

  @AfterEach
  public void clearDB() throws ScriptException {
    // runInitScript() pulls all the String text from the SQL file and just calls executeDatabaseScript(),
    // so it is OK to use runInitScript() again even though we aren't initializing the DB for the first time here.
    // runInitScript() is a poorly-named function.
    ScriptUtils.runInitScript(dbDelegate, "clearDB.sql");
  }

  //// INTEGRATION TESTS ////

  /**
   * Verifies the simplest deposit case.
   * The customer's Balance in the Customers table should be increased,
   * and the Deposit should be logged in the TransactionHistory table.
   * 
   * Assumes that the customer's account is in the simplest state
   * (not in overdraft, account is not frozen due to too many transaction disputes, etc.)
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testSimpleDeposit() throws SQLException, ScriptException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES);

    // Prepare Deposit Form to Deposit $12.34 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 12.34; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT); 

    // verify that there are no logs in TransactionHistory table before Deposit
    assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TransactionHistory;", Integer.class));

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(customer1DepositFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM TransactionHistory;");
  
    // verify that customer1's data is still the only data populated in Customers table
    assertEquals(1, customersTableData.size());
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(CUSTOMER1_ID, (String)customer1Data.get("CustomerID"));

    // verify customer balance was increased by $12.34
    double CUSTOMER1_EXPECTED_FINAL_BALANCE = CUSTOMER1_BALANCE + CUSTOMER1_AMOUNT_TO_DEPOSIT;
    double CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_FINAL_BALANCE);
    assertEquals(CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES, (int)customer1Data.get("Balance"));

    // verify that the Deposit is the only log in TransactionHistory table
    assertEquals(1, transactionHistoryTableData.size());
    
    // verify that the Deposit's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);
  }

  /**
   * Verifies the simplest withdraw case.
   * The customer's Balance in the Customers table should be decreased,
   * and the Withdraw should be logged in the TransactionHistory table.
   * 
   * Assumes that the customer's account is in the simplest state
   * (not already in overdraft, the withdraw does not put customer in overdraft,
   *  account is not frozen due to too many transaction disputes, etc.)
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testSimpleWithdraw() throws SQLException, ScriptException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES);

    // Prepare Withdraw Form to Withdraw $12.34 from customer 1's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 12.34; // user input is in dollar amount, not pennies.
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW); // user input is in dollar amount, not pennies.

    // verify that there are no logs in TransactionHistory table before Withdraw
    assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM TransactionHistory;", Integer.class));

    // store timestamp of when Withdraw request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Withdraw Request is sent: " + timeWhenWithdrawRequestSent);

    // send request to the Withdraw Form's POST handler in MvcController
    controller.submitWithdraw(customer1WithdrawFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM TransactionHistory;");
  
    // verify that customer1's data is still the only data populated in Customers table
    assertEquals(1, customersTableData.size());
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(CUSTOMER1_ID, (String)customer1Data.get("CustomerID"));

    // verify customer balance was decreased by $12.34
    double CUSTOMER1_EXPECTED_FINAL_BALANCE = CUSTOMER1_BALANCE - CUSTOMER1_AMOUNT_TO_WITHDRAW;
    double CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_FINAL_BALANCE);
    assertEquals(CUSTOMER1_EXPECTED_FINAL_BALANCE_IN_PENNIES, (int)customer1Data.get("Balance"));

    // verify that the Withdraw is the only log in TransactionHistory table
    assertEquals(1, transactionHistoryTableData.size());

    // verify that the Withdraw's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_WITHDRAW);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenWithdrawRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);
  }

  /**
   * Verifies the case where a customer withdraws more than their available balance.
   * The customer's main balance should be set to $0, and their Overdraft balance
   * should be the remaining withdraw amount with interest applied.
   * 
   * This Withdraw should still be recorded in the TransactionHistory table.
   * 
   * A few Assertions are omitted to remove clutter since they are already
   * checked in detail in testSimpleWithdraw().
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testWithdrawTriggersOverdraft() throws SQLException, ScriptException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES);

    // Prepare Withdraw Form to Withdraw $150 from customer 1's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 150; // user input is in dollar amount, not pennies.
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW); // user input is in dollar amount, not pennies.

    // store timestamp of when Withdraw request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Withdraw Request is sent: " + timeWhenWithdrawRequestSent);

    // send request to the Withdraw Form's POST handler in MvcController
    controller.submitWithdraw(customer1WithdrawFormInputs);

    // fetch updated customer1 data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM TransactionHistory;");
    
    // verify that customer1's main balance is now 0
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(0, (int)customer1Data.get("Balance"));

    // verify that customer1's Overdraft balance is equal to the remaining withdraw amount with interest applied
    // (convert to pennies before applying interest rate to avoid floating point roundoff errors when applying the interest rate)
    int CUSTOMER1_ORIGINAL_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_WITHDRAW);
    int CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_BEFORE_INTEREST_IN_PENNIES = CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES - CUSTOMER1_ORIGINAL_BALANCE_IN_PENNIES;
    int CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES = MvcControllerIntegTestHelpers.applyOverdraftInterest(CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_BEFORE_INTEREST_IN_PENNIES);
    System.out.println("Expected Overdraft Balance in pennies: " + CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES);
    assertEquals(CUSTOMER1_EXPECTED_OVERDRAFT_BALANCE_AFTER_INTEREST_IN_PENNIES, (int)customer1Data.get("OverdraftBalance"));

    // verify that the Withdraw's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenWithdrawRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);
  }

  /**
   * The customer will be given an initial balance of $100 and will withdraw $1099.
   * This will test the scenario where the withdraw excess amount, which is $100 - $1099 = -$999,
   * results in a valid overdraft balance, but once the 2% interest rate is applied, will cross the limit
   * of $1000. 
   * 
   * This test checks to make sure that the customer's balance stays the same as before due to a failed 
   * withdraw request, and checks that the TransactionHistory table is empty.
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testWithdrawOverdraftLimitExceeded() throws SQLException, ScriptException { 
    //initialize customer1 with a balance of $100. this will be represented as pennies in DB.
    double CUSTOMER1_BALANCE = 100;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES);

    //Prepare Withdraw Form to withdraw $1099 from this customer's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 1099; 
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW);

    //Store the timestamp of the withdraw request to verify it in the TransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when withdraw request sent: " + timeWhenWithdrawRequestSent);

    //Check the response when the withdraw request is submitted. This should return the user back to the home screen due to an invalid request
    String responsePage = controller.submitWithdraw(customer1WithdrawFormInputs);
    assertEquals("welcome", responsePage);

    //Fetch customer1's data from DB
    List<Map<String, Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");

    //Since the request did not go through, the balance is supposed to stay the same.
    Map<String, Object> customer1Data = customersTableData.get(0);
    assertEquals(CUSTOMER1_BALANCE_IN_PENNIES, (int)customer1Data.get("Balance"));

    //Checks to make sure that the overdraft balance was not increased
    assertEquals(0, (int)customer1Data.get("OverdraftBalance"));

    //check that TransactionHistory table is empty
    List<Map<String, Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM TransactionHistory;");
    assertTrue(transactionHistoryTableData.isEmpty());

  }

  /**
   * Verifies the case where a customer is in overdraft and deposits an amount
   * that exceeds their overdraft balance. The customer's OverdraftBalance
   * in the Customers table should be set to $0, and their main Balance
   * should be set to the excess deposit amount.
   * 
   * This Deposit should be logged in the OverdraftLogs table since it is a repayment.
   * 
   * This Deposit should still be recorded in the TransactionHistory table.
   * 
   * A few Assertions are omitted to remove clutter since they are already
   * checked in detail in testSimpleDeposit().
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testDepositOverdraftClearedWithExcess() throws SQLException, ScriptException {
    // initialize customer1 with an overdraft balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    int CUSTOMER1_MAIN_BALANCE_IN_PENNIES = 0;
    double CUSTOMER1_OVERDRAFT_BALANCE = 123.45;
    int CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_OVERDRAFT_BALANCE);
    int CUSTOMER1_NUM_FRAUD_REVERSALS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_MAIN_BALANCE_IN_PENNIES, CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES, CUSTOMER1_NUM_FRAUD_REVERSALS);

    // Prepare Deposit Form to Deposit $150 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 150; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT); 

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory and OverdraftLogs tables later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(customer1DepositFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM TransactionHistory;");
    List<Map<String,Object>> overdraftLogsTableData = jdbcTemplate.queryForList("SELECT * FROM OverdraftLogs;");

    // verify that customer's overdraft balance is now $0
    Map<String,Object> customer1Data = customersTableData.get(0);
    assertEquals(0, (int)customer1Data.get("OverdraftBalance"));

    // verify that the customer's main balance is now $50 due to the excess deposit amount
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    int CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES = CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES;
    int CUSTOMER1_EXPECTED_MAIN_BALANCE_IN_PENNIES = CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES - CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_MAIN_BALANCE_IN_PENNIES, (int)customer1Data.get("Balance"));

    // verify that the deposit is logged properly in the OverdraftLogs table
    Map<String,Object> customer1OverdraftLog = overdraftLogsTableData.get(0);
    MvcControllerIntegTestHelpers.checkOverdraftLog(customer1OverdraftLog, timeWhenDepositRequestSent, CUSTOMER1_ID, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES, CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES, 0);

    // verify that the Deposit's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);
  }

  /**
   * Verifies the case where a customer is in overdraft and deposits an amount
   * that still leaves some leftover Overdraft balance. The customer's OverdraftBalance
   * in the Customers table should be set to $0, and their main Balance
   * should still be $0 in the MySQL DB.
   * 
   * This Deposit should be logged in the OverdraftLogs table since it is a repayment.
   * 
   * This Deposit should still be recorded in the TransactionHistory table.
   * 
   * A few Assertions are omitted to remove clutter since they are already
   * checked in detail in testSimpleDeposit().
   * 
   * @throws SQLException
   * @throws ScriptException
   */
  @Test
  public void testDepositOverdraftNotCleared() throws SQLException, ScriptException {
    // initialize customer1 with an overdraft balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    int CUSTOMER1_MAIN_BALANCE_IN_PENNIES = 0;
    double CUSTOMER1_OVERDRAFT_BALANCE = 123.45;
    int CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_OVERDRAFT_BALANCE);
    int CUSTOMER1_NUM_FRAUD_REVERSALS = 0;
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_MAIN_BALANCE_IN_PENNIES, CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES, CUSTOMER1_NUM_FRAUD_REVERSALS);

    // Prepare Deposit Form to Deposit $50 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 50; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT); 

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory and OverdraftLogs tables later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(customer1DepositFormInputs);

    // fetch updated data from the DB
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM TransactionHistory;");
    List<Map<String,Object>> overdraftLogsTableData = jdbcTemplate.queryForList("SELECT * FROM OverdraftLogs;");

    // verify that customer's overdraft balance is now $100
    Map<String,Object> customer1Data = customersTableData.get(0);
    int CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES = CUSTOMER1_OVERDRAFT_BALANCE_IN_PENNIES;
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    int CUSTOMER1_EXPECTED_FINAL_OVERDRAFT_BALANCE_IN_PENNIES = CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES - CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_FINAL_OVERDRAFT_BALANCE_IN_PENNIES, (int)customer1Data.get("OverdraftBalance"));

    // verify that the customer's main balance is still $0
    int CUSTOMER1_EXPECTED_MAIN_BALANCE_IN_PENNIES = 0;
    assertEquals(CUSTOMER1_EXPECTED_MAIN_BALANCE_IN_PENNIES, (int)customer1Data.get("Balance"));

    // verify that the deposit is logged properly in the OverdraftLogs table
    Map<String,Object> customer1OverdraftLog = overdraftLogsTableData.get(0);
    MvcControllerIntegTestHelpers.checkOverdraftLog(customer1OverdraftLog, timeWhenDepositRequestSent, CUSTOMER1_ID, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES, CUSTOMER1_ORIGINAL_OVERDRAFT_BALANCE_IN_PENNIES, CUSTOMER1_EXPECTED_FINAL_OVERDRAFT_BALANCE_IN_PENNIES);

    // verify that the Withdraw's details are accurately logged in the TransactionHistory table
    Map<String,Object> customer1TransactionLog = transactionHistoryTableData.get(0);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1TransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);
  }

  /**
   * Verifies the transaction dispute feature on a simple deposit transaction.
   * The customer's main balance should go back to the original value after the
   * reversal of the deposit. The customer's numFraudReversals counter should
   * also be incremented by 1.
   * 
   * The initial Deposit should be recorded in the TransactionHistory table.
   * 
   * The reversed Deposit should be recorded in the TransactionHistory table
   * as a Withdraw.
   * 
   * Some verifications are not done on the initial Deposit since it is already
   * checked in detail in testSimpleDeposit().
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testReversalOfSimpleDeposit() throws SQLException, ScriptException, InterruptedException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    // No overdraft or numFraudReversals.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES);

    // Prepare Deposit Form to Deposit $12.34 (to make sure this works for non-whole dollar amounts) to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_DEPOSIT = 12.34; // user input is in dollar amount, not pennies.
    User customer1DepositFormInputs = new User();
    customer1DepositFormInputs.setUsername(CUSTOMER1_ID);
    customer1DepositFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1DepositFormInputs.setAmountToDeposit(CUSTOMER1_AMOUNT_TO_DEPOSIT);

    // store timestamp of when Deposit request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenDepositRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Deposit Request is sent: " + timeWhenDepositRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDeposit(customer1DepositFormInputs);

    // verify customer1's balance after the deposit
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    Map<String,Object> customer1Data = customersTableData.get(0);
    double CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT = CUSTOMER1_BALANCE + CUSTOMER1_AMOUNT_TO_DEPOSIT; 
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT);
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_DEPOSIT_IN_PENNIES, (int)customer1Data.get("Balance"));

    // sleep for 1 second to ensure the timestamps of Deposit and Reversal are different (and sortable) in TransactionHistory table
    Thread.sleep(1000);

    // Prepare Reversal Form to reverse the Deposit
    User customer1ReversalFormInputs = customer1DepositFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(1); // reverse the most recent transaction

    // store timestamp of when Reversal request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenReversalRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Reversal Request is sent: " + timeWhenReversalRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDispute(customer1ReversalFormInputs);

    // re-fetch updated customer data from the DB
    customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    customer1Data = customersTableData.get(0);

    // verify that customer1's balance is back to the original value
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES = CUSTOMER1_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES, (int)customer1Data.get("Balance"));

    // verify that customer1's numFraudReversals counter is now 1
    assertEquals(1, (int) customer1Data.get("NumFraudReversals"));

    // fetch transaction data from the DB in chronological order
    // the more recent transaction should be the Reversal, and the older transaction should be the Deposit
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM TransactionHistory ORDER BY Timestamp ASC;");
    Map<String,Object> customer1DepositTransactionLog = transactionHistoryTableData.get(0);
    Map<String,Object> customer1ReversalTransactionLog = transactionHistoryTableData.get(1);

    // verify that the Deposit's details are accurately logged in the TransactionHistory table
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_DEPOSIT);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1DepositTransactionLog, timeWhenDepositRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);

    // verify that the Reversal is accurately logged in the TransactionHistory table as a Withdraw
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES;
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1ReversalTransactionLog, timeWhenReversalRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);
  }

  /**
   * Verifies the transaction dispute feature on a simple withdraw transaction.
   * The customer's main balance should go back to the original value after the
   * reversal of the withdraw. The customer's numFraudReversals counter should
   * also be incremented by 1.
   * 
   * The initial Withdraw should be recorded in the TransactionHistory table.
   * 
   * The reversed Withdraw should be recorded in the TransactionHistory table
   * as a Deposit.
   * 
   * Some verifications are not done on the initial Withdraw since it is already
   * checked in detail in testSimpleWithdraw().
   * 
   * @throws SQLException
   * @throws ScriptException
   * @throws InterruptedException
   */
  @Test
  public void testReversalOfSimpleWithdraw() throws SQLException, ScriptException, InterruptedException {
    // initialize customer1 with a balance of $123.45 (to make sure this works for non-whole dollar amounts). represented as pennies in the DB.
    // No overdraft or numFraudReversals.
    double CUSTOMER1_BALANCE = 123.45;
    int CUSTOMER1_BALANCE_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_BALANCE);
    MvcControllerIntegTestHelpers.addCustomerToDB(dbDelegate, CUSTOMER1_ID, CUSTOMER1_PASSWORD, CUSTOMER1_FIRST_NAME, CUSTOMER1_LAST_NAME, CUSTOMER1_BALANCE_IN_PENNIES);

    // Prepare Withdraw Form to Withdraw $12.34 to customer 1's account.
    double CUSTOMER1_AMOUNT_TO_WITHDRAW = 12.34; // user input is in dollar amount, not pennies.
    User customer1WithdrawFormInputs = new User();
    customer1WithdrawFormInputs.setUsername(CUSTOMER1_ID);
    customer1WithdrawFormInputs.setPassword(CUSTOMER1_PASSWORD);
    customer1WithdrawFormInputs.setAmountToWithdraw(CUSTOMER1_AMOUNT_TO_WITHDRAW);

    // store timestamp of when Withdraw request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenWithdrawRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Withdraw Request is sent: " + timeWhenWithdrawRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitWithdraw(customer1WithdrawFormInputs);

    // verify customer1's balance after the withdraw
    List<Map<String,Object>> customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    Map<String,Object> customer1Data = customersTableData.get(0);
    double CUSTOMER1_EXPECTED_BALANCE_AFTER_WITHDRAW = CUSTOMER1_BALANCE - CUSTOMER1_AMOUNT_TO_WITHDRAW;
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_EXPECTED_BALANCE_AFTER_WITHDRAW);
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_WITHDRAW_IN_PENNIES, (int)customer1Data.get("Balance"));

    // sleep for 1 second to ensure the timestamps of Deposit and Reversal are different (and sortable) in TransactionHistory table
    Thread.sleep(1000);

    // Prepare Reversal Form to reverse the Deposit
    User customer1ReversalFormInputs = customer1WithdrawFormInputs;
    customer1ReversalFormInputs.setNumTransactionsAgo(1); // reverse the most recent transaction

    // store timestamp of when Reversal request is sent to verify timestamps in the TransactionHistory table later
    LocalDateTime timeWhenReversalRequestSent = MvcControllerIntegTestHelpers.fetchCurrentTimeAsLocalDateTimeNoMilliseconds();
    System.out.println("Timestamp when Reversal Request is sent: " + timeWhenReversalRequestSent);

    // send request to the Deposit Form's POST handler in MvcController
    controller.submitDispute(customer1ReversalFormInputs);

    // re-fetch updated customer data from the DB
    customersTableData = jdbcTemplate.queryForList("SELECT * FROM Customers;");
    customer1Data = customersTableData.get(0);

    // verify that customer1's balance is back to the original value
    int CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES = CUSTOMER1_BALANCE_IN_PENNIES;
    assertEquals(CUSTOMER1_EXPECTED_BALANCE_AFTER_REVERSAL_IN_PENNIES, (int)customer1Data.get("Balance"));

    // verify that customer1's numFraudReversals counter is now 1
    assertEquals(1, (int) customer1Data.get("NumFraudReversals"));

    // fetch transaction data from the DB in chronological order
    // the more recent transaction should be the Reversal, and the older transaction should be the Withdraw
    List<Map<String,Object>> transactionHistoryTableData = jdbcTemplate.queryForList("SELECT * FROM TransactionHistory ORDER BY Timestamp ASC;");
    Map<String,Object> customer1WithdrawTransactionLog = transactionHistoryTableData.get(0);
    Map<String,Object> customer1ReversalTransactionLog = transactionHistoryTableData.get(1);

    // verify that the Withdraw's details are accurately logged in the TransactionHistory table
    int CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES = MvcControllerIntegTestHelpers.convertDollarsToPennies(CUSTOMER1_AMOUNT_TO_WITHDRAW);
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1WithdrawTransactionLog, timeWhenWithdrawRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_WITHDRAW_ACTION, CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES);

    // verify that the Reversal is accurately logged in the TransactionHistory table as a Deposit
    int CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES = CUSTOMER1_AMOUNT_TO_WITHDRAW_IN_PENNIES;
    MvcControllerIntegTestHelpers.checkTransactionLog(customer1ReversalTransactionLog, timeWhenReversalRequestSent, CUSTOMER1_ID, MvcController.TRANSACTION_HISTORY_DEPOSIT_ACTION, CUSTOMER1_AMOUNT_TO_DEPOSIT_IN_PENNIES);
  }

}

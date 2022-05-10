package net.testudobank;

import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;

import lombok.Getter;
import lombok.Setter;

public class User {
  @Setter @Getter
	private String username;

  @Setter @Getter
	private String password;

  @Setter @Getter
  private String firstName;

  @Setter @Getter
  private String lastName;

  @Setter  @Getter @PositiveOrZero
	private double balance;

  @Setter @Getter @PositiveOrZero
	private double overDraftBalance;

  @Setter @Getter
	private String logs;

  @Setter @Getter
  private String transactionHist;

  @Setter @Getter @Positive
  private double amountToDeposit;

  @Setter @Getter @Positive
  private double amountToWithdraw;

  @Setter @Getter
  private int numTransactionsAgo;

  @Setter @Getter
  private double amountToTransfer;

  @Setter @Getter
  private String transferRecipientID;

  @Setter @Getter
  private double ethPrice;

  @Setter @Getter
  public double bitPrice = 36389.50;

  @Setter @Getter
  private double ethBalance;

  @Setter @Getter
  private double crypto;

  @Setter @Getter
  private double bitBalance;

  @Setter @Getter
  private String cryptoHist;
  
  @Setter @Getter
  private boolean isTransfer;

  @Setter @Getter
  private String transferHist;

  @Setter @Getter @Positive
  private double amountToBuyCrypto;

  @Setter @Getter @Positive
  private double amountToBuyBit;

  @Setter @Getter @Positive
  private double amountToBuyEth;

  @Setter @Getter @Positive
  private double amountToConvertCrypto;

  
  @Setter @Getter @Positive
  private double amountToSellEth;

  @Setter @Getter @Positive
  private double amountToSellBit;

	@Override
	public String toString() {
		return "User [username=" + username + ", password=" + password + ", balance=" + balance + "]";
	}

}

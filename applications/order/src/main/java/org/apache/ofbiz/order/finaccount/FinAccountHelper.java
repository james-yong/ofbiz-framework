/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ofbiz.order.finaccount;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilNumber;
import org.apache.ofbiz.base.util.UtilValidate;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * A package of methods for improving efficiency of financial accounts services
 */
public final class FinAccountHelper {
    private static final String MODULE = FinAccountHelper.class.getName();
    /**
     * Precision: since we're just adding and subtracting, the interim figures should have one more decimal place of precision than the final numbers
     */
    private static final int DECIMALS = UtilNumber.getBigDecimalScale("finaccount.decimals");
    private static final RoundingMode ROUNDING = UtilNumber.getRoundingMode("finaccount.rounding");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(DECIMALS, ROUNDING);
    private static final String GC_FIN_ACCOUNT_TYPE = "GIFTCERT_ACCOUNT";
    // pool of available characters for account codes, here numbers plus uppercase characters
    private static char[] charPool = new char[10 + 26];

    static {
        int j = 0;
        for (int i = "0".charAt(0); i <= "9".charAt(0); i++) {
            charPool[j++] = (char) i;
        }
        for (int i = "A".charAt(0); i <= "Z".charAt(0); i++) {
            charPool[j++] = (char) i;
        }
    }

    protected FinAccountHelper() {
    }

    public static int getDecimals() {
        return DECIMALS;
    }

    public static RoundingMode getRounding() {
        return ROUNDING;
    }

    public static BigDecimal getZero() {
        return ZERO;
    }

    public static String getGiftCertFinAccountTypeId() {
        return GC_FIN_ACCOUNT_TYPE;
    }

    /**
     * A convenience method which adds transactions.get(0).get(fieldName) to initialValue, all done in BigDecimal to decimals and rounding
     * @param initialValue the initial value
     * @param transactions a List of GenericValue objects of transactions
     * @param fieldName    the field name to get the value from the transaction
     * @param decimals     number of decimals
     * @param rounding     how to rounding
     * @return the new value in a BigDecimal field
     * @throws GenericEntityException
     */
    public static BigDecimal addFirstEntryAmount(BigDecimal initialValue, List<GenericValue> transactions, String fieldName, int decimals,
                                                 RoundingMode rounding) throws GenericEntityException {
        if ((transactions != null) && (transactions.size() == 1)) {
            GenericValue firstEntry = transactions.get(0);
            if (firstEntry.get(fieldName) != null) {
                BigDecimal valueToAdd = firstEntry.getBigDecimal(fieldName);
                return initialValue.add(valueToAdd).setScale(decimals, rounding);
            } else {
                return initialValue;
            }
        } else {
            return initialValue;
        }
    }

    /**
     * Returns a unique randomly generated account code for FinAccount.finAccountCode composed of uppercase letters and numbers
     * @param codeLength length of code in number of characters
     * @param delegator  the delegator
     * @return returns a unique randomly generated account code for FinAccount.finAccountCode composed of uppercase letters and numbers
     * @throws GenericEntityException
     */
    public static String getNewFinAccountCode(int codeLength, Delegator delegator) throws GenericEntityException {

        // keep generating new account codes until a unique one is found
        SecureRandom r = new SecureRandom();
        boolean foundUniqueNewCode = false;
        StringBuilder newAccountCode = null;
        long count = 0;

        while (!foundUniqueNewCode) {
            newAccountCode = new StringBuilder(codeLength);
            for (int i = 0; i < codeLength; i++) {
                newAccountCode.append(charPool[r.nextInt(charPool.length)]);
            }

            GenericValue existingAccountWithCode = EntityQuery.use(delegator).from("FinAccount")
                    .where("finAccountCode", newAccountCode.toString()).queryFirst();
            if (existingAccountWithCode == null) {
                foundUniqueNewCode = true;
            }

            count++;
            if (count > 999999) {
                throw new GenericEntityException("Unable to locate unique FinAccountCode! Length [" + codeLength + "]");
            }
        }

        return newAccountCode.toString();
    }

    /**
     * Gets the first (and should be only) FinAccount based on finAccountCode, which will be cleaned up to be only uppercase and alphanumeric
     * @param finAccountCode the financial account code
     * @param delegator      the delegator
     * @return gets the first financial account by code
     * @throws GenericEntityException
     */
    public static GenericValue getFinAccountFromCode(String finAccountCode, Delegator delegator) throws GenericEntityException {
        if (finAccountCode == null) {
            return null;
        }
        finAccountCode = finAccountCode.toUpperCase(Locale.getDefault()).replaceAll("[^0-9A-Z]", "");

        // now look for the account
        List<GenericValue> accounts = EntityQuery.use(delegator).from("FinAccount")
                .where("finAccountCode", finAccountCode)
                .filterByDate().queryList();
        if (UtilValidate.isEmpty(accounts)) {
            // OK to display - not a code anyway
            Debug.logWarning("No fin account found for account code [" + finAccountCode + "]", MODULE);
            return null;
        } else if (accounts.size() > 1) {
            // This should never happen, but don't display the code if it does -- it is supposed to be encrypted!
            Debug.logError("Multiple fin accounts found", MODULE);
            return null;
        } else {
            return accounts.get(0);
        }
    }


    /**
     * Sum of all DEPOSIT and ADJUSTMENT transactions minus all WITHDRAWAL transactions whose transactionDate is before asOfDateTime
     * @param finAccountId the financial account id
     * @param asOfDateTime the validity date
     * @param delegator    the delegator
     * @return returns the sum of all DEPOSIT and ADJUSTMENT transactions minus all WITHDRAWAL transactions
     * @throws GenericEntityException
     */
    public static BigDecimal getBalance(String finAccountId, Timestamp asOfDateTime, Delegator delegator) throws GenericEntityException {
        if (asOfDateTime == null) asOfDateTime = UtilDateTime.nowTimestamp();

        BigDecimal incrementTotal = ZERO;  // total amount of transactions which increase balance
        BigDecimal decrementTotal = ZERO;  // decrease balance

        // find the sum of all transactions which increase the value
        List<GenericValue> transSums = EntityQuery.use(delegator)
                .select("amount")
                .from("FinAccountTransSum")
                .where(EntityCondition.makeCondition("finAccountId", EntityOperator.EQUALS, finAccountId),
                        EntityCondition.makeCondition("transactionDate", EntityOperator.LESS_THAN_EQUAL_TO, asOfDateTime),
                        EntityCondition.makeCondition(UtilMisc.toList(
                                EntityCondition.makeCondition("finAccountTransTypeId", EntityOperator.EQUALS, "DEPOSIT"),
                                EntityCondition.makeCondition("finAccountTransTypeId", EntityOperator.EQUALS, "ADJUSTMENT")),
                                EntityOperator.OR))
                .queryList();
        incrementTotal = addFirstEntryAmount(incrementTotal, transSums, "amount", (DECIMALS + 1), ROUNDING);

        // now find sum of all transactions with decrease the value
        transSums = EntityQuery.use(delegator)
                .select("amount")
                .from("FinAccountTransSum")
                .where(EntityCondition.makeCondition("finAccountId", EntityOperator.EQUALS, finAccountId),
                        EntityCondition.makeCondition("transactionDate", EntityOperator.LESS_THAN_EQUAL_TO, asOfDateTime),
                        EntityCondition.makeCondition("finAccountTransTypeId", EntityOperator.EQUALS, "WITHDRAWAL"))
                .queryList();
        decrementTotal = addFirstEntryAmount(decrementTotal, transSums, "amount", (DECIMALS + 1), ROUNDING);

        // the net balance is just the incrementTotal minus the decrementTotal
        return incrementTotal.subtract(decrementTotal).setScale(DECIMALS, ROUNDING);
    }

    /**
     * Returns the net balance (see above) minus the sum of all authorization amounts which are not expired and were authorized by the as of date
     * @param finAccountId the financial account id
     * @param asOfDateTime the validity date
     * @param delegator    the delegator
     * @return returns the net balance (see above) minus the sum of all authorization amounts which are not expired
     * @throws GenericEntityException
     */
    public static BigDecimal getAvailableBalance(String finAccountId, Timestamp asOfDateTime, Delegator delegator) throws GenericEntityException {
        if (asOfDateTime == null) asOfDateTime = UtilDateTime.nowTimestamp();

        BigDecimal netBalance = getBalance(finAccountId, asOfDateTime, delegator);

        // find sum of all authorizations which are not expired and which were authorized before as of time
        List<GenericValue> authSums = EntityQuery.use(delegator)
                .select("amount")
                .from("FinAccountAuthSum")
                .where(EntityCondition.makeCondition("finAccountId", EntityOperator.EQUALS, finAccountId),
                        EntityCondition.makeCondition("authorizationDate", EntityOperator.LESS_THAN_EQUAL_TO, asOfDateTime))
                .queryList();

        BigDecimal authorizationsTotal = addFirstEntryAmount(ZERO, authSums, "amount", (DECIMALS + 1), ROUNDING);

        // the total available balance is transactions total minus authorizations total
        return netBalance.subtract(authorizationsTotal).setScale(DECIMALS, ROUNDING);
    }

    public static boolean validateFinAccount(GenericValue finAccount) {
        return false;
    }

    /**
     * Validates a FinAccount's PIN number
     * @param delegator    the delegator
     * @param finAccountId the financial account id
     * @param pinNumber    a pin number
     * @return true if the bin is valid
     */
    public static boolean validatePin(Delegator delegator, String finAccountId, String pinNumber) {
        GenericValue finAccount = null;
        try {
            finAccount = EntityQuery.use(delegator).from("FinAccount").where("finAccountId", finAccountId).queryOne();
        } catch (GenericEntityException e) {
            Debug.logError(e, MODULE);
        }

        if (finAccount != null) {
            String dbPin = finAccount.getString("finAccountCode");
            Debug.logInfo("FinAccount Pin Validation: [Sent: " + pinNumber + "] [Actual: " + dbPin + "]", MODULE);
            if (dbPin != null && dbPin.equals(pinNumber)) {
                return true;
            }
        } else {
            Debug.logInfo("FinAccount record not found (" + finAccountId + ")", MODULE);
        }
        return false;
    }

    public static boolean checkFinAccountNumber(String number) {
        number = number.replaceAll("\\D", "");
        return UtilValidate.sumIsMod10(UtilValidate.getLuhnSum(number));
    }
}

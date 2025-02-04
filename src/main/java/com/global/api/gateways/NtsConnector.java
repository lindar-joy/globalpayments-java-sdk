package com.global.api.gateways;

import com.global.api.builders.AuthorizationBuilder;
import com.global.api.builders.ManagementBuilder;
import com.global.api.builders.ReportBuilder;
import com.global.api.builders.TransactionBuilder;
import com.global.api.entities.Transaction;
import com.global.api.entities.enums.*;
import com.global.api.entities.exceptions.ApiException;
import com.global.api.entities.exceptions.GatewayException;
import com.global.api.network.NetworkMessageHeader;
import com.global.api.network.entities.NtsObjectParam;
import com.global.api.network.entities.UserDataTag;
import com.global.api.network.entities.nts.*;
import com.global.api.network.enums.NTSCardTypes;
import com.global.api.paymentMethods.IPaymentMethod;
import com.global.api.paymentMethods.TransactionReference;
import com.global.api.serviceConfigs.GatewayConnectorConfig;
import com.global.api.terminals.DeviceMessage;
import com.global.api.terminals.abstractions.IDeviceMessage;
import com.global.api.utils.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

public class NtsConnector extends GatewayConnectorConfig {

    private NtsMessageCode messageCode;

    private static TransactionReference getReferencesObject(TransactionBuilder builder, NtsResponse ntsResponse, NTSCardTypes cardTypes) {
        IPaymentMethod paymentMethod = builder.getPaymentMethod();
        TransactionType transactionType = builder.getTransactionType();
        AuthorizationBuilder authBuilder;
        Map<String, String> userData = new HashMap<>();
        TransactionReference reference = NtsUtils.prepareTransactionReference(ntsResponse);
        if (paymentMethod.getPaymentMethodType().equals(PaymentMethodType.Credit) &&
                (transactionType.equals(TransactionType.Auth) || transactionType.equals(TransactionType.Sale))) {
            if (cardTypes.equals(NTSCardTypes.MastercardFleet) || cardTypes.equals(NTSCardTypes.VisaFleet) || cardTypes.equals(NTSCardTypes.Mastercard) || cardTypes.equals(NTSCardTypes.Visa) || cardTypes.equals(NTSCardTypes.AmericanExpress) || cardTypes.equals(NTSCardTypes.Discover)) {
                reference = NtsUtils.prepareTransactionReference(ntsResponse);
                userData = reference.getUserDataTag();
                if (userData != null) {
                    reference.setSystemTraceAuditNumber(userData.get("03"));
                    reference.setPartialApproval(userData.getOrDefault("04", "N").equals("Y"));
                    reference.setOriginalApprovedAmount(StringUtils.toAmount(userData.get("05")));
                }
                // authorization builder
                if (builder instanceof AuthorizationBuilder) {
                    authBuilder = (AuthorizationBuilder) builder;
                    reference.setOriginalAmount(authBuilder.getAmount());
                    reference.setOriginalPaymentMethod(authBuilder.getPaymentMethod());
                    reference.setPaymentMethodType(authBuilder.getPaymentMethod().getPaymentMethodType());
                }
            }

            if (builder instanceof AuthorizationBuilder) {
                if (cardTypes.equals(NTSCardTypes.WexFleet)) {
                    if (transactionType.equals(TransactionType.Auth)) {
                        NtsAuthCreditResponseMapper ntsAuthCreditResponseMapper = (NtsAuthCreditResponseMapper) ntsResponse.getNtsResponseMessage();
                        String hostResponseArea = ntsAuthCreditResponseMapper.getCreditMapper().getHostResponseArea();
                        if (!StringUtils.isNullOrEmpty(hostResponseArea) && userData != null) {
                            StringParser responseParser = new StringParser(hostResponseArea);
                            String amount = responseParser.readString(7);
                            userData.put("ApprovedAmount", amount);
                            reference.setOriginalApprovedAmount(new BigDecimal(amount));
                            if (builder.getTagData() != null) {
                                userData.put("AvailableProducts", responseParser.readString(49));
                                userData.put("EmvDataLength", responseParser.readString(4));
                                userData.put("EvmData", responseParser.readRemaining());
                            }
                        }
                    } else if (transactionType.equals(TransactionType.Sale)) {
                        NtsSaleCreditResponseMapper ntsSaleCreditResponseMapper = (NtsSaleCreditResponseMapper) ntsResponse.getNtsResponseMessage();
                        String hostResponseArea = ntsSaleCreditResponseMapper.getCreditMapper().getHostResponseArea();
                        if (!StringUtils.isNullOrEmpty(hostResponseArea) && userData != null) {
                            StringParser responseParser = new StringParser(hostResponseArea);
                            String amount = responseParser.readString(7);
                            userData.put("ApprovedAmount", amount);
                            reference.setOriginalApprovedAmount(new BigDecimal(amount));
                            userData.put("ReceiptText", responseParser.readRemaining());
                            if (builder.getTagData() != null) {
                                userData.put("EmvDataLength", responseParser.readString(4));
                                userData.put("EvmData", responseParser.readRemaining());
                            }
                        }
                    }
                } else if (cardTypes.equals(NTSCardTypes.FleetWide) || cardTypes.equals(NTSCardTypes.FuelmanFleet)) {
                    if (transactionType.equals(TransactionType.Auth) && userData != null) {
                        NtsAuthCreditResponseMapper ntsAuthCreditResponseMapper = (NtsAuthCreditResponseMapper) ntsResponse.getNtsResponseMessage();
                        String hostResponseArea = ntsAuthCreditResponseMapper.getCreditMapper().getHostResponseArea();
                        StringParser responseParser = new StringParser(hostResponseArea);
                        String amount = responseParser.readString(7);
                        userData.put("ApprovedAmount", amount);
                        reference.setOriginalApprovedAmount(new BigDecimal(amount));
                        userData.put("ReceiptText", responseParser.readRemaining());
                    } else if (transactionType.equals(TransactionType.Sale) && userData != null) {
                        NtsSaleCreditResponseMapper ntsSaleCreditResponseMapper = (NtsSaleCreditResponseMapper) ntsResponse.getNtsResponseMessage();
                        String hostResponseArea = ntsSaleCreditResponseMapper.getCreditMapper().getHostResponseArea();
                        StringParser responseParser = new StringParser(hostResponseArea);
                        String amount = responseParser.readString(7);
                        userData.put("ApprovedAmount", amount);
                        reference.setOriginalApprovedAmount(new BigDecimal(amount));
                        userData.put("ReceiptText", responseParser.readRemaining());
                    }
                }
            }
            authBuilder = (AuthorizationBuilder) builder;
            reference.setOriginalAmount(authBuilder.getAmount());
            reference.setOriginalPaymentMethod(authBuilder.getPaymentMethod());
            reference.setPaymentMethodType(authBuilder.getPaymentMethod().getPaymentMethodType());
        } else if (paymentMethod.getPaymentMethodType().equals(PaymentMethodType.Gift)
                && ntsResponse.getNtsResponseMessage() instanceof NtsAuthCreditResponseMapper) {
            NtsAuthCreditResponseMapper ntsAuthCreditResponseMapper = (NtsAuthCreditResponseMapper) ntsResponse.getNtsResponseMessage();
            String hostResponseArea = ntsAuthCreditResponseMapper.getCreditMapper().getHostResponseArea();
            StringParser responseParser = new StringParser(hostResponseArea);
            reference.setOriginalTransactionTypeIndicator(ReverseStringEnumMap.parse(responseParser.readString(8).trim(), TransactionTypeIndicator.class));
            reference.setSystemTraceAuditNumber(responseParser.readString(6));
            userData.put("RemainingBalance", responseParser.readString(6));
        } else if(paymentMethod.getPaymentMethodType().equals(PaymentMethodType.Debit)
                && transactionType != TransactionType.DataCollect){
            NtsDebitResponse ntsDebitResponse = (NtsDebitResponse) ntsResponse.getNtsResponseMessage();
            reference.setOriginalTransactionCode(ntsDebitResponse.getTransactionCode());
        } else if(paymentMethod.getPaymentMethodType().equals(PaymentMethodType.EBT)
            && transactionType != TransactionType.DataCollect){
            NtsEbtResponse ntsEbtResponse = (NtsEbtResponse) ntsResponse.getNtsResponseMessage();
            reference.setOriginalTransactionCode(ntsEbtResponse.getTransactionCode());
        }
        if (builder instanceof AuthorizationBuilder) {
            authBuilder = (AuthorizationBuilder) builder;
            reference.setOriginalAmount(authBuilder.getAmount());
            reference.setOriginalPaymentMethod(authBuilder.getPaymentMethod());
            reference.setPaymentMethodType(authBuilder.getPaymentMethod().getPaymentMethodType());
        }
        reference.setUserDataTag(userData);
        return reference;
    }

    public Transaction processAuthorization(AuthorizationBuilder builder) throws ApiException {
        messageCode = builder.getNtsRequestMessageHeader().getNtsMessageCode();

        //message body
        MessageWriter request = new MessageWriter();
        IPaymentMethod paymentMethod = builder.getPaymentMethod();
        NTSCardTypes cardType = NtsUtils.mapCardType(paymentMethod);
        String userData = setUserData(builder, paymentMethod, cardType);

        // Request parameters.
        NtsObjectParam ntsObjectParam = new NtsObjectParam();
        ntsObjectParam.setNtsBuilder(builder);
        ntsObjectParam.setNtsRequest(request);
        ntsObjectParam.setNtsAcceptorConfig(acceptorConfig);
        ntsObjectParam.setNtsUserData(userData);
        ntsObjectParam.setNtsEnableLogging(isEnableLogging());
        ntsObjectParam.setNtsBatchProvider(batchProvider);
        ntsObjectParam.setNtsCardType(cardType);
        ntsObjectParam.setBinTerminalId(binTerminalId);
        ntsObjectParam.setBinTerminalType(binTerminalType);
        ntsObjectParam.setInputCapabilityCode(inputCapabilityCode);
        ntsObjectParam.setSoftwareVersion(softwareVersion);
        ntsObjectParam.setLogicProcessFlag(logicProcessFlag);
        ntsObjectParam.setTerminalType(terminalType);
        ntsObjectParam.setUnitNumber(unitNumber);
        ntsObjectParam.setTerminalId(terminalId);

        //Preparing the request
        request = NtsRequestObjectFactory.getNtsRequestObject(ntsObjectParam);
        NtsUtils.log("Request with header in text ", request.getMessageRequest().toString());
        return sendRequest(request, builder);
    }

    private String setUserData(TransactionBuilder<Transaction> builder, IPaymentMethod paymentMethod, NTSCardTypes cardType) {
        String userData = "";
        if (cardType != null && isUserDataPresent(builder, paymentMethod, cardType)) {
            messageCode = builder.getNtsRequestMessageHeader().getNtsMessageCode();
            if (isNonBankCard(cardType)

                    || isDataCollectForNonFleetBankCard(cardType, builder.getTransactionType())) {
                userData = UserDataTag.getNonBankCardUserData(builder, cardType, messageCode, acceptorConfig);
                NtsUtils.log("Non Bank Card user Data :", userData);
            } else {
                userData = UserDataTag.getBankCardUserData(builder, paymentMethod, cardType, messageCode, acceptorConfig);
                NtsUtils.log("Bank card user Data :", userData);
            }
        } else if (builder.getTransactionType() == TransactionType.BatchClose) {
            userData = UserDataTag.getRequestToBalanceUserData(builder);
            NtsUtils.log("Request to balance user Data :", userData);
        }
        return userData;
    }

    private <T extends TransactionBuilder<Transaction>> Transaction sendRequest(MessageWriter messageData, T builder) throws ApiException {
        NtsUtils.log("Request Message length:", String.valueOf(messageData.getMessageRequest().length()));

        try {
            int messageLength = messageData.getMessageRequest().length() + 2;
            MessageWriter req = new MessageWriter();
            req.add(messageLength, 2);
            req.add(messageData.getMessageRequest().toString());

            IDeviceMessage buildMessage = new DeviceMessage(req.toArray());
            NtsUtils.log("Final Request with header and data", buildMessage.toString());
            byte[] responseBuffer = send(buildMessage);
            return mapResponse(responseBuffer, builder);

        } catch (GatewayException exc) {
            exc.setHost(currentHost.getValue());
            throw exc;
        } catch (Exception ex) {
            throw new ApiException(ex.getMessage());
        }

    }

    private <T extends TransactionBuilder<Transaction>> Transaction mapResponse(byte[] buffer, T builder) throws ApiException {
        Transaction result = new Transaction();
        IPaymentMethod paymentMethod = builder.getPaymentMethod();
        MessageReader mr = new MessageReader(buffer);
        NTSCardTypes cardType = NtsUtils.mapCardType(paymentMethod);
        StringParser sp = new StringParser(buffer);
        NtsUtils.log("Final Response", sp.getBuffer());
        NtsResponse ntsResponse = NtsResponseObjectFactory.getNtsResponseObject(mr.readBytes((int) mr.getLength()), builder);

        if (Boolean.FALSE.equals(isAllowedResponseCode(ntsResponse.getNtsResponseMessageHeader().getNtsNetworkMessageHeader().getResponseCode()))) {
            throw new GatewayException(
                    String.format("Unexpected response from gateway: %s %s", ntsResponse.getNtsResponseMessageHeader().getNtsNetworkMessageHeader().getResponseCode().getValue(),
                            ntsResponse.getNtsResponseMessageHeader().getNtsNetworkMessageHeader().getResponseCode().toString()),
                    ntsResponse.getNtsResponseMessageHeader().getNtsNetworkMessageHeader().getResponseCode().getValue(),
                    ntsResponse.getNtsResponseMessageHeader().getNtsNetworkMessageHeader().getResponseCode().name());
        } else {
            NtsResponseMessageHeader ntsResponseMessageHeader = ntsResponse.getNtsResponseMessageHeader();
            result.setResponseCode(ntsResponseMessageHeader.getNtsNetworkMessageHeader().getResponseCode().getValue());
            result.setNtsResponse(ntsResponse);
            if (paymentMethod != null) {
                result.setTransactionReference(getReferencesObject(builder, ntsResponse, cardType));
            }
        }
        return result;
    }

    private Boolean isAllowedResponseCode(NtsHostResponseCode code) {
        return code == NtsHostResponseCode.Success
                || code == NtsHostResponseCode.PartiallyApproved
                || code == NtsHostResponseCode.Denial
                || code == NtsHostResponseCode.VelocityReferral
                || code == NtsHostResponseCode.AvsReferralForFullyOrPartially;
    }

    public Transaction manageTransaction(ManagementBuilder builder) throws ApiException {
        //message header section
        NtsUtils.log("###########", "Management Builder Request Header");

        messageCode = builder.getNtsRequestMessageHeader().getNtsMessageCode();

        //message body
        MessageWriter request = new MessageWriter();
        IPaymentMethod paymentMethod = builder.getPaymentMethod();
        NTSCardTypes cardType = NtsUtils.mapCardType(paymentMethod);
        String userData = setUserData(builder, paymentMethod, cardType);

        // Request parameters.
        NtsObjectParam ntsObjectParam = new NtsObjectParam();
        ntsObjectParam.setNtsBuilder(builder);
        ntsObjectParam.setNtsRequest(request);
        ntsObjectParam.setNtsAcceptorConfig(acceptorConfig);
        ntsObjectParam.setNtsUserData(userData);
        ntsObjectParam.setNtsEnableLogging(isEnableLogging());
        ntsObjectParam.setNtsBatchProvider(batchProvider);
        ntsObjectParam.setNtsCardType(cardType);
        ntsObjectParam.setBinTerminalId(binTerminalId);
        ntsObjectParam.setBinTerminalType(binTerminalType);
        ntsObjectParam.setInputCapabilityCode(inputCapabilityCode);
        ntsObjectParam.setSoftwareVersion(softwareVersion);
        ntsObjectParam.setLogicProcessFlag(logicProcessFlag);
        ntsObjectParam.setTerminalType(terminalType);
        ntsObjectParam.setUnitNumber(unitNumber);
        ntsObjectParam.setTerminalId(terminalId);

        request = NtsRequestObjectFactory.getNtsRequestObject(ntsObjectParam);
        NtsUtils.log("Request with header in text ", request.getMessageRequest().toString());
        return sendRequest(request, builder);
    }

    public <T> T processReport(ReportBuilder<T> builder, Class<T> clazz) throws ApiException {
        return null;
    }

    public String serializeRequest(AuthorizationBuilder builder) throws ApiException {
        return null;
    }

    @Override
    public NetworkMessageHeader sendKeepAlive() throws ApiException {
        return null;
    }

    public boolean supportsHostedPayments() {
        return false;
    }

    /**
     * Check the given card is BankCard type or not.
     *
     * @param cardType
     * @return True: if card type is non bank card.
     */
    private boolean isNonBankCard(NTSCardTypes cardType) {
        return (cardType.equals(NTSCardTypes.StoredValueOrHeartlandGiftCard)
                || cardType.equals(NTSCardTypes.WexFleet)
                || cardType.equals(NTSCardTypes.VoyagerFleet)
                || cardType.equals(NTSCardTypes.FleetOne)
                || cardType.equals(NTSCardTypes.FuelmanFleet)
                || cardType.equals(NTSCardTypes.FleetWide));
    }

    /**
     * Check the given card is non-fleet BankCard and DataCollect request.
     *
     * @param cardType
     * @param transactionType
     * @return True: if card type is non-fleet BankCard and DataCollect.
     */
    private boolean isDataCollectForNonFleetBankCard(NTSCardTypes cardType, TransactionType transactionType) {
        return transactionType == TransactionType.DataCollect
                && (cardType == NTSCardTypes.Mastercard
                || cardType == NTSCardTypes.Visa
                || cardType == NTSCardTypes.AmericanExpress
                || cardType == NTSCardTypes.Discover
                || cardType == NTSCardTypes.StoredValueOrHeartlandGiftCard
                || cardType == NTSCardTypes.PinDebit);
    }

    private boolean isUserDataPresent(TransactionBuilder<Transaction> builder, IPaymentMethod paymentMethod, NTSCardTypes cardType) {
        TransactionType transactionType = builder.getTransactionType();
        if (messageCode.equals(NtsMessageCode.PinDebit) && transactionType == TransactionType.DataCollect)
            return true;
        else if (messageCode.equals(NtsMessageCode.PinDebit) || messageCode.equals(NtsMessageCode.Mail)
                || messageCode.equals(NtsMessageCode.UtilityMessage))
            return false;
        else if (transactionType.equals(TransactionType.Reversal)
                && !cardType.equals(NTSCardTypes.WexFleet)
                && !cardType.equals(NTSCardTypes.StoredValueOrHeartlandGiftCard))
            return false;
        else if (paymentMethod.getPaymentMethodType().equals(PaymentMethodType.EBT))
            return false;
        else
            return true;
    }

}

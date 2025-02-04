package com.global.api.entities;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

@Accessors(chain = true)
@Getter
@Setter
public class Product {
    private String productId;
    private String productName;
    private String description;
    private Integer quantity;
    private BigDecimal unitPrice;
    private boolean gift;
    private String unitCurrency;
    private String type;
    private String risk;
    private BigDecimal taxAmount;
}
package com.yourapp.ai.tools;

import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class OrderTools {

    @Tool(
            name = "getOrderStatus",
            description = "Get the current status of an order by orderId. Use when user asks about an order status."
    )
    public Map<String, Object> getOrderStatus(String orderId) {

        // Stubbed data for now (later this can be DB / REST / Kafka)
        return Map.of(
                "orderId", orderId,
                "status", "IN_TRANSIT",
                "estimatedDelivery", "2026-01-07"
        );
    }
}

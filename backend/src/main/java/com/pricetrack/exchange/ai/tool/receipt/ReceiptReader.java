package com.pricetrack.exchange.ai.tool.receipt;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.ai.tool.read.ToolReadFacade.ReceiptInput;

/** Takes only server-resolved authorized input, never a caller-supplied URL/hash. */
public interface ReceiptReader {
    ObjectNode read(ReceiptInput input);
}

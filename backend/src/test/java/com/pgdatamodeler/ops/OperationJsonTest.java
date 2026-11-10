package com.pgdatamodeler.ops;

import com.pgdatamodeler.Fixtures;
import com.pgdatamodeler.model.DataModel;
import com.pgdatamodeler.service.Dtos.OperationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JSON shape must match frontend/src/model/types.ts. */
class OperationJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesFrontendMessage() throws Exception {
        String json = """
                {"clientOpId":"abc","author":"jan","op":{"type":"moveTable","tableId":"t1","x":10,"y":20}}
                """;
        OperationRequest req = mapper.readValue(json, OperationRequest.class);
        assertEquals(new ModelOperation.MoveTable("t1", 10, 20), req.op());
        assertEquals("abc", req.clientOpId());
    }

    @Test
    void roundTripsTableOperationWithTypeTag() throws Exception {
        ModelOperation op = new ModelOperation.UpsertTable(Fixtures.shop().tables().get(1));
        String json = mapper.writeValueAsString(op);
        assertTrue(json.contains("\"type\":\"upsertTable\""), json);
        assertEquals(op, mapper.readValue(json, ModelOperation.class));
    }

    @Test
    void roundTripsWholeDocument() throws Exception {
        DataModel shop = Fixtures.shop();
        assertEquals(shop, mapper.readValue(mapper.writeValueAsString(shop), DataModel.class));
    }
}

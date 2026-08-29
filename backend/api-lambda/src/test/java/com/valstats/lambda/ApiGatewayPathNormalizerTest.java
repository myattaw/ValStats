package com.valstats.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ApiGatewayPathNormalizerTest {

    @Test
    void restoresEncodedRawPathForRiotIdsContainingSpaces() {
        var http = new APIGatewayV2HTTPEvent.RequestContext.Http();
        http.setPath("/api/valorant/account/EVERYONE LIES/207");
        var requestContext = new APIGatewayV2HTTPEvent.RequestContext();
        requestContext.setHttp(http);
        var event = new APIGatewayV2HTTPEvent();
        event.setRawPath("/api/valorant/account/EVERYONE LIES/207");
        event.setRequestContext(requestContext);

        assertSame(event, ApiGatewayPathNormalizer.normalize(event));
        assertEquals("EVERYONE LIES", ApiGatewayPathCodec.decodeSegment(
                event.getRequestContext().getHttp().getPath().split("/")[4]));
    }

    @Test
    void doesNotDoubleEncodeApiGatewayRawPath() {
        var http = new APIGatewayV2HTTPEvent.RequestContext.Http();
        http.setPath("/api/valorant/account/mentally chill/207");
        var requestContext = new APIGatewayV2HTTPEvent.RequestContext();
        requestContext.setHttp(http);
        var event = new APIGatewayV2HTTPEvent();
        event.setRawPath("/api/valorant/account/mentally%20chill/207");
        event.setRequestContext(requestContext);

        ApiGatewayPathNormalizer.normalize(event);

        assertEquals("mentally chill", ApiGatewayPathCodec.decodeSegment(
                event.getRequestContext().getHttp().getPath().split("/")[4]));
    }

    @Test
    void leavesIncompleteEventsUntouched() {
        var event = new APIGatewayV2HTTPEvent();

        assertSame(event, ApiGatewayPathNormalizer.normalize(event));
    }

}

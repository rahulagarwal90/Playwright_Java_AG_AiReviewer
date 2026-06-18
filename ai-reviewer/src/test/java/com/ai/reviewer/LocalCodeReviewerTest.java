package com.ai.reviewer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class LocalCodeReviewerTest {

    @Test
    void testRunReviewWithNoChanges() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        LocalCodeReviewer reviewer = spy(new LocalCodeReviewer(mockClient));

        // Mock git diff returning empty string
        doReturn("").when(reviewer).getGitDiff();

        CompletableFuture<String> resultFuture = reviewer.runReview();
        String result = resultFuture.get();

        assertEquals("No changes", result);
        // HttpClient should not be called
        verify(mockClient, never()).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void testRunReviewWithChangesAndSuccessfulResponse() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\"message\":{\"content\":\"LGTM! No issues found with brittle locators.\"}}");

        CompletableFuture<HttpResponse<String>> futureResponse = CompletableFuture.completedFuture(mockResponse);

        // We mock HttpClient.sendAsync to return a completed future with our mock response
        when(mockClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn((CompletableFuture) futureResponse);

        LocalCodeReviewer reviewer = spy(new LocalCodeReviewer(mockClient));

        // Stub git diff returning changes
        String dummyDiff = "diff --git a/test.txt b/test.txt\n" +
                           "--- a/test.txt\n" +
                           "+++ b/test.txt\n" +
                           "@@ -1,1 +1,1 @@\n" +
                           "-old_value\n" +
                           "+new_value\n";
        doReturn(dummyDiff).when(reviewer).getGitDiff();

        CompletableFuture<String> resultFuture = reviewer.runReview();
        String result = resultFuture.get();

        assertEquals("LGTM! No issues found with brittle locators.", result);

        // Verify request details
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClient).sendAsync(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

        HttpRequest request = requestCaptor.getValue();
        assertEquals("POST", request.method());
        assertEquals(URI.create("http://localhost:11434/api/chat"), request.uri());
        assertEquals("application/json", request.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void testRunReviewWithHttpErrorResponse() throws Exception {
        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        when(mockResponse.statusCode()).thenReturn(500);
        when(mockResponse.body()).thenReturn("Internal Server Error");

        CompletableFuture<HttpResponse<String>> futureResponse = CompletableFuture.completedFuture(mockResponse);

        when(mockClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn((CompletableFuture) futureResponse);

        LocalCodeReviewer reviewer = spy(new LocalCodeReviewer(mockClient));
        doReturn("+ int port = 8080;").when(reviewer).getGitDiff();

        CompletableFuture<String> resultFuture = reviewer.runReview();

        // Expect Exception due to non-200 status code
        assertThrows(Exception.class, resultFuture::get);
    }
}

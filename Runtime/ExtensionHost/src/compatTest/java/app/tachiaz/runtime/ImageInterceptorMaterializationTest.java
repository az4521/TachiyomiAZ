package app.tachiaz.runtime;

import eu.kanade.tachiyomi.source.model.FilterList;
import eu.kanade.tachiyomi.source.online.HttpSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Proves image responses pass through extension-owned OkHttp interceptors. */
public final class ImageInterceptorMaterializationTest {
    private static final byte[] EXPECTED = new byte[] {
        (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4
    };

    private ImageInterceptorMaterializationTest() {
    }

    public static void main(String[] arguments) throws Exception {
        FixtureSource source = new FixtureSource();
        Path destination = Files.createTempFile("tachiyomiaz-image-", ".bin");
        try {
            String response = ExtensionHost.materializeImage(
                source,
                "https://fixture.invalid/generated-image",
                null,
                destination.toString()
            );
            if (!source.interceptorInvoked) {
                throw new AssertionError("The extension interceptor was bypassed");
            }
            if (source.imageRequestInvoked) {
                throw new AssertionError(
                    "A manga cover was incorrectly sent through imageRequest(Page)"
                );
            }
            if (!Arrays.equals(EXPECTED, Files.readAllBytes(destination))) {
                throw new AssertionError("The materialized image data changed");
            }
            if (!response.contains("\\\"contentType\\\":\\\"image/png\\\"")) {
                throw new AssertionError("The image media type was not detected");
            }

            source.interceptorInvoked = false;
            ExtensionHost.materializeImage(
                source,
                "https://fixture.invalid/generated-page#scramble",
                "",
                destination.toString()
            );
            if (!source.imageRequestInvoked) {
                throw new AssertionError(
                    "A chapter page bypassed imageRequest(Page)"
                );
            }
            if (!source.interceptorInvoked) {
                throw new AssertionError(
                    "The extension interceptor was bypassed for a chapter page"
                );
            }
        } finally {
            Files.deleteIfExists(destination);
        }
        System.out.println("Image interceptor materialization test passed");
    }

    private static final class FixtureSource extends HttpSource {
        private boolean interceptorInvoked;
        private boolean imageRequestInvoked;
        private final OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor((Interceptor.Chain chain) -> {
                interceptorInvoked = true;
                if (
                    chain.request().url().encodedPath().contains("generated-page") &&
                    !"scramble".equals(chain.request().url().fragment())
                ) {
                    throw new AssertionError(
                        "The encrypted-image fragment was lost before the " +
                            "extension interceptor"
                    );
                }
                if (
                    chain.request().url().encodedPath().contains("generated-page") &&
                    !"page".equals(chain.request().header("X-Image-Request"))
                ) {
                    throw new AssertionError(
                        "The request returned by imageRequest(Page) was not " +
                            "executed"
                    );
                }
                return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(
                        MediaType.get("application/octet-stream"),
                        EXPECTED
                    ))
                    .build();
            })
            .build();

        @Override
        public String getName() {
            return "Image interceptor fixture";
        }

        @Override
        public String getLang() {
            return "en";
        }

        @Override
        public String getBaseUrl() {
            return "https://fixture.invalid";
        }

        @Override
        public boolean getSupportsLatest() {
            return false;
        }

        @Override
        public FilterList getFilterList() {
            return new FilterList(Collections.emptyList());
        }

        @Override
        public OkHttpClient getClient() {
            return client;
        }

        @Override
        protected Request imageRequest(
            eu.kanade.tachiyomi.source.model.Page page
        ) {
            imageRequestInvoked = true;
            return new Request.Builder()
                .url(page.getImageUrl())
                .header("X-Image-Request", "page")
                .build();
        }
    }
}

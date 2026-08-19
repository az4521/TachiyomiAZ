package app.tachiaz.runtime;

import eu.kanade.tachiyomi.source.model.FilterList;
import eu.kanade.tachiyomi.source.model.MangasPage;
import eu.kanade.tachiyomi.source.online.HttpSource;
import java.util.Collections;
import rx.Observable;

/**
 * Proves the Mihon 1.6 host contract: callers use the suspend API, while the
 * host's CatalogueSource default delegates to Rx for legacy extensions.
 */
public final class MihonExtensionLib16FallbackTest {
    private MihonExtensionLib16FallbackTest() {
    }

    public static void main(String[] arguments) throws Exception {
        LegacyRxSource source = new LegacyRxSource();
        Object result = ExtensionHost.invokeSuspend(
            source,
            "getPopularManga",
            new Class<?>[] { int.class },
            7
        );
        if (!(result instanceof MangasPage)) {
            throw new AssertionError("Expected a MangasPage");
        }
        MangasPage page = (MangasPage) result;
        if (source.requestedPage != 7) {
            throw new AssertionError(
                "Suspend API did not delegate to fetchPopularManga"
            );
        }
        if (!page.getMangas().isEmpty() || page.getHasNextPage()) {
            throw new AssertionError("Unexpected legacy Rx fixture result");
        }
        System.out.println(
            "Mihon extension-lib 1.6 suspend-to-Rx fallback test passed"
        );
    }

    private static final class LegacyRxSource extends HttpSource {
        int requestedPage;

        @Override
        public String getName() {
            return "Legacy Rx fixture";
        }

        @Override
        public String getLang() {
            return "en";
        }

        @Override
        public String getBaseUrl() {
            return "https://example.invalid";
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
        @SuppressWarnings("deprecation")
        public Observable<MangasPage> fetchPopularManga(int page) {
            requestedPage = page;
            return Observable.just(
                new MangasPage(Collections.emptyList(), false)
            );
        }
    }
}

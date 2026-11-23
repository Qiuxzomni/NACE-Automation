package com.nace.automation;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

final class AppLanguage {
    static final String ENGLISH = "en";
    static final String ROMANIAN = "ro";
    static final String SPANISH = "es";

    private static final String PREFERENCES = "app_language";
    private static final String LANGUAGE_TAG = "language_tag";

    private AppLanguage() {}

    static String current(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(LANGUAGE_TAG, ENGLISH);
    }

    static boolean set(Context context, String language) {
        if (language.equals(current(context))) return false;
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(LANGUAGE_TAG, language).apply();
        return true;
    }

    static Context localize(Context context) {
        Configuration configuration = new Configuration();
        configuration.setLocale(Locale.forLanguageTag(current(context)));
        return context.createConfigurationContext(configuration);
    }

    static String text(Context context, String english, String romanian, String spanish) {
        switch (current(context)) {
            case ROMANIAN: return romanian;
            case SPANISH: return spanish;
            default: return english;
        }
    }
}

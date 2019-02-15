package com.launchdarkly.android.flagstore.sharedprefs;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.support.annotation.NonNull;
import android.util.Pair;

import com.launchdarkly.android.flagstore.Flag;
import com.launchdarkly.android.flagstore.FlagStore;
import com.launchdarkly.android.flagstore.FlagStoreUpdateType;
import com.launchdarkly.android.flagstore.FlagUpdate;
import com.launchdarkly.android.flagstore.StoreUpdatedListener;
import com.launchdarkly.android.response.GsonCache;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import timber.log.Timber;

public class SharedPrefsFlagStore implements FlagStore {

    private static final String SHARED_PREFS_BASE_KEY = "LaunchDarkly-";
    private final String prefsKey;
    private Application application;
    private SharedPreferences sharedPreferences;
    private StoreUpdatedListener storeUpdatedListener;

    public SharedPrefsFlagStore(@NonNull Application application, @NonNull String identifier) {
        this.application = application;
        this.prefsKey = SHARED_PREFS_BASE_KEY + identifier + "-flags";
        this.sharedPreferences = application.getSharedPreferences(prefsKey, Context.MODE_PRIVATE);
    }

    @SuppressLint("ApplySharedPref")
    @Override
    public void delete() {
        sharedPreferences.edit().clear().commit();
        sharedPreferences = null;

        File file = new File(application.getFilesDir().getParent() + "/shared_prefs/" + prefsKey + ".xml");
        Timber.i("Deleting SharedPrefs file:%s", file.getAbsolutePath());

        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @Override
    public void clear() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.clear();
        editor.apply();
    }

    @Override
    public boolean containsKey(String key) {
        return sharedPreferences.contains(key);
    }

    @Nullable
    @Override
    public Flag getFlag(String flagKey) {
        String flagData = sharedPreferences.getString(flagKey, null);
        if (flagData == null)
            return null;

        return GsonCache.getGson().fromJson(flagData, Flag.class);
    }

    private Pair<String, FlagStoreUpdateType> applyFlagUpdateNoCommit(SharedPreferences.Editor editor, FlagUpdate flagUpdate) {
        String flagKey = flagUpdate.flagToUpdate();
        Flag flag = getFlag(flagKey);
        Flag newFlag = flagUpdate.updateFlag(flag);
        if (flag != null && newFlag == null) {
            editor.remove(flagKey);
            return new Pair<>(flagKey, FlagStoreUpdateType.FLAG_DELETED);
        } else if (flag == null && newFlag != null) {
            String flagData = GsonCache.getGson().toJson(newFlag);
            editor.putString(flagKey, flagData);
            return new Pair<>(flagKey, FlagStoreUpdateType.FLAG_CREATED);
        } else if (flag != newFlag) {
            String flagData = GsonCache.getGson().toJson(newFlag);
            editor.putString(flagKey, flagData);
            return new Pair<>(flagKey, FlagStoreUpdateType.FLAG_UPDATED);
        }
        return null;
    }

    // TODO synchronize listeners
    @Override
    public void applyFlagUpdate(FlagUpdate flagUpdate) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        Pair<String, FlagStoreUpdateType> update = applyFlagUpdateNoCommit(editor, flagUpdate);
        editor.apply();
        if (update != null && storeUpdatedListener != null) {
            storeUpdatedListener.onStoreUpdate(update.first, update.second);
        }
    }

    private ArrayList<Pair<String, FlagStoreUpdateType>> applyFlagUpdatesNoCommit(SharedPreferences.Editor editor, List<? extends FlagUpdate> flagUpdates) {
        ArrayList<Pair<String, FlagStoreUpdateType>> updates = new ArrayList<>();
        for (FlagUpdate flagUpdate : flagUpdates) {
            Pair<String, FlagStoreUpdateType> update = applyFlagUpdateNoCommit(editor, flagUpdate);
            if (update != null) {
                updates.add(update);
            }
        }
        return updates;
    }

    private void informListenersOfUpdateList(List<Pair<String, FlagStoreUpdateType>> updates) {
        if (storeUpdatedListener != null) {
            for (Pair<String, FlagStoreUpdateType> update : updates) {
                storeUpdatedListener.onStoreUpdate(update.first, update.second);
            }
        }
    }

    @Override
    public void applyFlagUpdates(List<? extends FlagUpdate> flagUpdates) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        ArrayList<Pair<String, FlagStoreUpdateType>> updates = applyFlagUpdatesNoCommit(editor, flagUpdates);
        editor.apply();
        informListenersOfUpdateList(updates);
    }

    @Override
    public void clearAndApplyFlagUpdates(List<? extends FlagUpdate> flagUpdates) {
        sharedPreferences.edit().clear().apply();
        applyFlagUpdates(flagUpdates);
    }

    @Override
    public List<Flag> getAllFlags() {
        Map<String, ?> flags = sharedPreferences.getAll();
        ArrayList<Flag> result = new ArrayList<>();
        for (Object entry : flags.values()) {
            if (entry instanceof String) {
                Flag flag = null;
                try {
                    flag = GsonCache.getGson().fromJson((String) entry, Flag.class);
                } catch (Exception ignored) {
                }
                if (flag == null) {
                    Timber.e("invalid flag found in flag store");
                } else {
                    result.add(flag);
                }
            } else {
                Timber.e("non-string found in flag store");
            }
        }
        return result;
    }

    @Override
    public void registerOnStoreUpdatedListener(StoreUpdatedListener storeUpdatedListener) {
        this.storeUpdatedListener = storeUpdatedListener;
    }

    @Override
    public void unregisterOnStoreUpdatedListener() {
        this.storeUpdatedListener = null;
    }
}

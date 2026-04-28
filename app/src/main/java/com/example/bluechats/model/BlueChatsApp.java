package com.example.bluechats.model;

import android.app.Application;
import android.content.Context;

public class BlueChatsApp extends Application {
    private static BlueChatsApp instance;
    private BleMeshManager meshManager;
    private CryptoManager cryptoManager;
    private String localId;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    public static BlueChatsApp getInstance() {
        return instance;
    }

    public static Context getAppContext() {
        return instance.getApplicationContext();
    }

    public void setMeshManager(BleMeshManager manager) {
        this.meshManager = manager;
    }

    public BleMeshManager getMeshManager() {
        return meshManager;
    }

    public void setCryptoManager(CryptoManager manager) {
        this.cryptoManager = manager;
    }

    public CryptoManager getCryptoManager() {
        return cryptoManager;
    }

    public void setLocalId(String id) {
        this.localId = id;
    }

    public String getLocalId() {
        return localId;
    }
}

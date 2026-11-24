package com.rmlala.madacourses;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Affiche l'application MADA COURSES PRO (index.html, copié dans les assets au build)
 * dans une WebView. Les exports (fichiers blob) passent par un pont natif, car une
 * WebView ne sait pas enregistrer seule les téléchargements de type blob.
 */
public class MainActivity extends Activity {

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST = 4107;

    // Intercepte les clics sur les liens de téléchargement blob et envoie le fichier au pont natif.
    private static final String DOWNLOAD_BRIDGE_JS =
        "(function(){" +
        "var orig=HTMLAnchorElement.prototype.click;" +
        "HTMLAnchorElement.prototype.click=function(){" +
        "  if(this.hasAttribute('download')&&this.href&&this.href.indexOf('blob:')===0){" +
        "    var name=this.getAttribute('download');" +
        "    fetch(this.href).then(function(r){return r.blob();}).then(function(b){" +
        "      var fr=new FileReader();" +
        "      fr.onload=function(){var s=fr.result;" +
        "        AndroidBridge.save(name,s.substring(s.indexOf(',')+1),b.type||'application/octet-stream');};" +
        "      fr.readAsDataURL(b);" +
        "    });" +
        "    return;" +
        "  }" +
        "  return orig.apply(this,arguments);" +
        "};" +
        "})();";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);   // l'application est en JavaScript
        s.setDomStorageEnabled(true);   // localStorage : données et sauvegardes
        s.setAllowFileAccess(true);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.addJavascriptInterface(new SecureStorageBridge(), "AppForgeNativeStorage");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "Sélecteur de sauvegarde indisponible", Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(DOWNLOAD_BRIDGE_JS, null);
            }
        });

        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null) {
            web.evaluateJavascript(
                "if(document.body.classList.contains('menu-open')){document.body.classList.remove('menu-open');'handled'}else{'no'}",
                value -> { if (value == null || value.contains("no")) finish(); });
        } else {
            super.onBackPressed();
        }
    }


    /**
     * Stockage des jetons AppForge chiffrés avec AES-256-GCM et une clé non exportable
     * conservée dans Android Keystore. Les méthodes sont synchrones pour l'adaptateur WebView.
     */
    private class SecureStorageBridge {
        private static final String PREFS = "mada_courses_cloud_secure_v1";
        private static final String KEY_ALIAS = "mada_courses_cloud_aes_v1";

        @JavascriptInterface
        public String get(String name) {
            try {
                if (!validName(name)) return null;
                String packed = getSharedPreferences(PREFS, MODE_PRIVATE).getString(name, null);
                if (packed == null) return null;
                String[] parts = packed.split(":", 2);
                if (parts.length != 2) return null;
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, getSecretKey(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
                return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }

        @JavascriptInterface
        public boolean set(String name, String value) {
            try {
                if (!validName(name) || value == null || value.length() > 1000000) return false;
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
                String packed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
                return getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(name, packed).commit();
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void remove(String name) {
            if (validName(name)) getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(name).apply();
        }

        private boolean validName(String name) {
            return name != null && name.length() > 0 && name.length() <= 120;
        }

        private SecretKey getSecretKey() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
            return generator.generateKey();
        }
    }

    /** Méthodes appelées depuis le JavaScript de la page. */
    public class Bridge {
        @JavascriptInterface
        public void save(String name, String base64, String mime) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                final String where = writeFile(name, mime, data);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Enregistré : " + where, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Échec de l'enregistrement", Toast.LENGTH_LONG).show());
            }
        }
    }

    private String writeFile(String name, String mime, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ : écriture directe dans Téléchargements, sans permission supplémentaire.
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                os.write(data);
            }
            return "Téléchargements/" + name;
        }
        // Android 6 à 9 : dossier Téléchargements propre à l'application, sans permission.
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = new File(dir, name);
        try (FileOutputStream os = new FileOutputStream(file)) {
            os.write(data);
        }
        return file.getAbsolutePath();
    }
}

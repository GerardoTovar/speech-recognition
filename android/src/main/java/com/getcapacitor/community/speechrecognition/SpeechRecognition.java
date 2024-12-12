package com.getcapacitor.community.speechrecognition;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;

@CapacitorPlugin(
    permissions = { @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = SpeechRecognition.SPEECH_RECOGNITION) }
)
public class SpeechRecognition extends Plugin implements Constants {

    public static final String TAG = "SpeechRecognition";
    private static final String LISTENING_EVENT = "listeningState";
    static final String SPEECH_RECOGNITION = "speechRecognition";

    private Receiver languageReceiver;
    private SpeechRecognizer speechRecognizer;

    private final AtomicBoolean isListening = new AtomicBoolean(false);

    private JSONArray previousPartialResults = new JSONArray();

    @Override
    public void load() {
        super.load();
        bridge.getWebView().post(() -> {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(bridge.getActivity());
            SpeechRecognitionListener listener = new SpeechRecognitionListener();
            speechRecognizer.setRecognitionListener(listener);
            Logger.info(getLogTag(), "SpeechRecognizer setup completed in load()");
        });
    }

    @PluginMethod
    public void available(PluginCall call) {
        Logger.info(getLogTag(), "Called for available(): " + isSpeechRecognitionAvailable());
        boolean val = isSpeechRecognitionAvailable();
        JSObject result = new JSObject();
        result.put("available", val);
        call.resolve(result);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (!isSpeechRecognitionAvailable()) {
            call.unavailable(NOT_AVAILABLE);
            return;
        }

        if (getPermissionState(SPEECH_RECOGNITION) != PermissionState.GRANTED) {
            call.reject(MISSING_PERMISSION);
            return;
        }

        Logger.info(getLogTag(), "Restarting recognition due to repeated start call");
        stopListening(); // Detiene la escucha actual
        
        // Agrega un retraso para liberar los recursos antes de reiniciar
        bridge.getWebView().postDelayed(() -> {
            releaseSpeechRecognizer(); // Libera los recursos
            isListening.set(false); // Restablece el estado
            // Continúa con el inicio del nuevo proceso después de liberar recursos
            initializeAndStart(call);
        }, 100);
    }

    private void initializeAndStart(PluginCall call) {
        String language = call.getString("language", Locale.getDefault().toString());
        int maxResults = call.getInt("maxResults", MAX_RESULTS);
        String prompt = call.getString("prompt", null);
        boolean partialResults = call.getBoolean("partialResults", false);
        boolean popup = call.getBoolean("popup", false);
        int allowForSilence = call.getInt("allowForSilence", 0);

        // Inicia un nuevo proceso de reconocimiento
        beginListening(language, maxResults, prompt, partialResults, popup, call, allowForSilence);
    }

    private void releaseSpeechRecognizer() {
        // Libera recursos del SpeechRecognizer sin importar el estado actual
        if (speechRecognizer != null) {
            try {
                Logger.info(getLogTag(), "Forcing release of SpeechRecognizer resources");
                speechRecognizer.cancel(); // Cancela cualquier operación en curso
                speechRecognizer.destroy(); // Libera recursos asignados
                JSObject ret = new JSObject();
                ret.put("status", "stopped");
                ret.put("metod", "releaseSpeechRecognizer");
                notifyListeners(LISTENING_EVENT, ret); // Notifica que el reconocimiento fue detenido
            } catch (Exception ex) {
                Logger.error(getLogTag(), "Error releasing SpeechRecognizer: " + ex.getMessage(), ex);
            } finally {
                speechRecognizer = null;
                isListening.set(false); // Asegúrate de restablecer el estado
            }
        }
    }


    @PluginMethod
    public void stop(final PluginCall call) {
        try {
            stopListening(); // Asegúrate de detener la escucha antes
            // Retrasa la liberación de recursos para evitar conflictos
            bridge.getWebView().postDelayed(() -> releaseSpeechRecognizer(), 100); 
            call.resolve(); // Indica que la operación se realizó correctamente
        } catch (Exception ex) {
            call.reject(ex.getLocalizedMessage());
        }
    }

    @PluginMethod
    public void getSupportedLanguages(PluginCall call) {
        if (languageReceiver == null) {
            languageReceiver = new Receiver(call);
        }

        List<String> supportedLanguages = languageReceiver.getSupportedLanguages();
        if (supportedLanguages != null) {
            JSONArray languages = new JSONArray(supportedLanguages);
            call.resolve(new JSObject().put("languages", languages));
            return;
        }

        Intent detailsIntent = new Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            detailsIntent.setPackage("com.google.android.googlequicksearchbox");
        }
        bridge.getActivity().sendOrderedBroadcast(detailsIntent, null, languageReceiver, null, Activity.RESULT_OK, null, null);
    }

    @PluginMethod
    public void isListening(PluginCall call) {
        call.resolve(new JSObject().put("listening", isListening.get()));
    }

    @ActivityCallback
    private void listeningResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }

        int resultCode = result.getResultCode();
        if (resultCode == Activity.RESULT_OK) {
            try {
                ArrayList<String> matchesList = result.getData().getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                JSObject resultObj = new JSObject();
                resultObj.put("matches", new JSArray(matchesList));
                call.resolve(resultObj);
            } catch (Exception ex) {
                call.reject(ex.getMessage());
            }
        } else {
            call.reject(Integer.toString(resultCode));
        }

        isListening.set(false);
    }

    private boolean isSpeechRecognitionAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(bridge.getContext());
    }

    private void beginListening(
        String language,
        int maxResults,
        String prompt,
        final boolean partialResults,
        boolean showPopup,
        PluginCall call,
        int allowForSilence
    ) {
        Logger.info(getLogTag(), "Beginning to listen for audible speech");

        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, bridge.getActivity().getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults);
        intent.putExtra("android.speech.extra.DICTATION_MODE", partialResults);

        if (allowForSilence > 0) {
            intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, allowForSilence);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, allowForSilence);
        }

        if (prompt != null) {
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
        }

        if (showPopup) {
            startActivityForResult(call, intent, "listeningResult");
        } else {
            if (isListening.compareAndSet(false, true)) { // Cambia a true solo si estaba false
                bridge.getWebView().post(() -> {
                    try {
                        releaseSpeechRecognizer();

                        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(bridge.getActivity());
                        SpeechRecognitionListener listener = new SpeechRecognitionListener();
                        listener.setCall(call);
                        listener.setPartialResults(partialResults);
                        speechRecognizer.setRecognitionListener(listener);
                        speechRecognizer.startListening(intent);
                        if (partialResults) {
                            call.resolve();
                        }
                    } catch (Exception ex) {
                        isListening.set(false); // Asegúrate de restaurar el estado en caso de error
                        call.reject(ex.getMessage());
                    }
                });
            } else {
                Logger.warn(getLogTag(), "Already listening, ignoring beginListening call");
            }
        }
    }

    private void stopListening() {
        if (isListening.compareAndSet(true, false)) { // Cambia a false solo si estaba true
            bridge.getWebView().post(() -> {
                try {
                    if (speechRecognizer != null) {
                        speechRecognizer.stopListening();
                    }
                } catch (Exception ex) {
                    Logger.error(getLogTag(), "Error stopping SpeechRecognizer: " + ex.getMessage(), ex);
                }
            });
        } else {
            Logger.warn(getLogTag(), "StopListening called, but was not listening");
        }
    }

    private class SpeechRecognitionListener implements RecognitionListener {

        private PluginCall call;
        private boolean partialResults;

        public void setCall(PluginCall call) {
            this.call = call;
        }

        public void setPartialResults(boolean partialResults) {
            this.partialResults = partialResults;
        }

        @Override
        public void onReadyForSpeech(Bundle params) {}

        @Override
        public void onBeginningOfSpeech() {
            if (isListening.get()) { // Solo notifica si el reconocimiento está activo
                JSObject ret = new JSObject();
                ret.put("status", "started_speech");
                ret.put("metod", "onBeginningOfSpeech");
                SpeechRecognition.this.notifyListeners(LISTENING_EVENT, ret);
            } else {
                Logger.warn(getLogTag(), "onBeginningOfSpeech called, but not in listening state");
            }
        }

        @Override
        public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBufferReceived(byte[] buffer) {}

        @Override
        public void onEndOfSpeech() {
            bridge.getWebView().post(() -> {
                JSObject ret = new JSObject();
                ret.put("status", "stopped_speech");
                ret.put("metod", "onEndOfSpeech");
                notifyListeners(LISTENING_EVENT, ret);
            });
        }

        @Override
        public void onError(int error) {
            if (isListening.compareAndSet(true, false)) { // Cambia el estado solo si estaba escuchando
                stopListening();
            }
            notifyListeners(LISTENING_EVENT, new JSObject().put("status", "error").put("error", getErrorText(error)));
            String errorMssg = getErrorText(error);
            if (this.call != null) {
                call.reject(errorMssg);
            }
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

            try {
                JSArray jsArray = new JSArray(matches);

                if (this.call != null) {
                    if (!this.partialResults) {
                        this.call.resolve(new JSObject().put("status", "success").put("matches", jsArray));
                    } else {
                        JSObject ret = new JSObject();
                        ret.put("matches", jsArray);
                        notifyListeners("partialResults", ret);
                    }
                }
            } catch (Exception ex) {
                this.call.resolve(new JSObject().put("status", "error").put("message", ex.getMessage()));
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches == null || matches.isEmpty()) {
                return; // No hay resultados parciales
            }
            synchronized (this) {
                JSArray matchesJSON = new JSArray(matches);
                try {
                    // Compara si los nuevos resultados son diferentes a los anteriores
                    if (!previousPartialResults.equals(matchesJSON)) {
                        // Clona los nuevos resultados parciales
                        previousPartialResults = new JSONArray(matches);

                        // Prepara la notificación
                        JSObject ret = new JSObject();
                        ret.put("matches", previousPartialResults);
                        notifyListeners("partialResults", ret); // Notifica solo si hubo cambios
                    }
                } catch (Exception ex) {
                    Logger.error(getLogTag(), "Error handling partial results: " + ex.getMessage(), ex);
                }
            }
        }

        @Override
        public void onSegmentResults(Bundle results) {
            ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

            try {
                JSArray jsArray = new JSArray(matches);

                if (this.call != null) {
                    if (!this.partialResults) {
                        this.call.resolve(new JSObject().put("status", "success").put("matches", jsArray));
                    } else {
                        JSObject ret = new JSObject();
                        ret.put("matches", jsArray);
                        notifyListeners("segmentResults", ret);
                    }
                }
            } catch (Exception ex) {
                this.call.resolve(new JSObject().put("status", "error").put("message", ex.getMessage()));
            }
        }

        @Override
        public void onEndOfSegmentedSession() {
            JSObject ret = new JSObject();
            notifyListeners("endOfSegmentedSession", ret);
        }

        @Override
        public void onEvent(int eventType, Bundle params) {}
    }

    private String getErrorText(int errorCode) {
        String message;
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                message = "Audio recording error";
                break;
            case SpeechRecognizer.ERROR_CLIENT:
                message = "Client side error";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                message = "Insufficient permissions";
                break;
            case SpeechRecognizer.ERROR_NETWORK:
                message = "Network error";
                break;
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                message = "Network timeout";
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
                message = "No match";
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                message = "RecognitionService busy";
                break;
            case SpeechRecognizer.ERROR_SERVER:
                message = "error from server";
                break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                message = "No speech input";
                break;
            default:
                message = "Didn't understand, please try again.";
                break;
        }
        return message;
    }
}

package com.example.ipwebcamapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_PORT = "port";
    private static final String KEY_PORTRAIT = "portrait";
    private static final String KEY_YOLO = "yolo";
    private static final String KEY_DRAW_BOXES = "draw_boxes";

    private EditText portEditText;
    private Button saveButton;
    private SwitchCompat portraitSwitch;
    private SwitchCompat yoloSwitch;
    private SwitchCompat drawBoxesSwitch;
    private SharedPreferences sharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        portEditText = findViewById(R.id.portEditText);
        saveButton = findViewById(R.id.saveButton);
        portraitSwitch = findViewById(R.id.portraitSwitch);
        yoloSwitch = findViewById(R.id.yoloSwitch);
        drawBoxesSwitch = findViewById(R.id.drawBoxesSwitch);
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Load saved port number
        int savedPort = sharedPreferences.getInt(KEY_PORT, 8080);
        portEditText.setText(String.valueOf(savedPort));

        // Load saved picture orientation
        portraitSwitch.setChecked(sharedPreferences.getBoolean(KEY_PORTRAIT, false));

        // Load saved detection settings
        yoloSwitch.setChecked(sharedPreferences.getBoolean(KEY_YOLO, false));
        drawBoxesSwitch.setChecked(sharedPreferences.getBoolean(KEY_DRAW_BOXES, false) && yoloSwitch.isChecked());
        drawBoxesSwitch.setEnabled(yoloSwitch.isChecked());

        // Boxes only exist while YOLO is running, so drawing them is only offered then
        yoloSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) {
                drawBoxesSwitch.setChecked(false);
            }
            drawBoxesSwitch.setEnabled(isChecked);
        });

        saveButton.setOnClickListener(v -> saveSettings());
    }

    private void saveSettings() {
        try {
            int portNumber = Integer.parseInt(portEditText.getText().toString());
            boolean yolo = yoloSwitch.isChecked();
            boolean drawBoxes = drawBoxesSwitch.isChecked() && yolo;
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt(KEY_PORT, portNumber);
            editor.putBoolean(KEY_PORTRAIT, portraitSwitch.isChecked());
            editor.putBoolean(KEY_YOLO, yolo);
            editor.putBoolean(KEY_DRAW_BOXES, drawBoxes);
            editor.apply();

            // Return the result to MainActivity
            Intent resultIntent = new Intent();
            resultIntent.putExtra("port", portNumber);
            resultIntent.putExtra(CameraStreamService.EXTRA_PORTRAIT, portraitSwitch.isChecked());
            resultIntent.putExtra(CameraStreamService.EXTRA_YOLO, yolo);
            resultIntent.putExtra(CameraStreamService.EXTRA_DRAW_BOXES, drawBoxes);
            setResult(RESULT_OK, resultIntent);
            finish(); // Close the activity
        } catch (NumberFormatException e) {
            portEditText.setError("Invalid port number");
        }
    }
}

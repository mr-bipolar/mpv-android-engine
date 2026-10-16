package com.mrbipolar.mpvengine.sample;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.mrbipolar.mpvengine.sample.databinding.ActivityMainBinding;
import com.mrbipolar.mpvengine.sample.player.MpvPlayerActivity;
import com.mrbipolar.mpvengine.sample.utils.ClickUtils;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        ClickUtils.applyTouchEffect(binding.btnPlay, v -> {

            final String url = binding.etStreamUrl.getText().toString();
            startActivity(MpvPlayerActivity.playStream(this, url));
        });
    }
}
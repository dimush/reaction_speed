package org.softosaurus.reactionspeed;

import android.os.Bundle;
import android.app.Activity;
import android.content.Intent;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.CheckBox;

import androidx.core.app.NavUtils;

public class OptionsActivity extends Activity {
	CheckBox cbUseTargetSounds;
	CheckBox cbUseStoneSounds;
	CheckBox cbUseVibrator;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_options);
        setResult(RESULT_CANCELED);
        cbUseTargetSounds = (CheckBox)findViewById(R.id.checkBox1);
		cbUseStoneSounds = (CheckBox)findViewById(R.id.checkBox2);
		cbUseVibrator = (CheckBox)findViewById(R.id.checkBox3);	
        Intent intent = getIntent();
        cbUseTargetSounds.setChecked(intent.getExtras().getBoolean("use_target_sounds"));
        cbUseStoneSounds.setChecked(intent.getExtras().getBoolean("use_stone_sounds"));
        cbUseVibrator.setChecked(intent.getExtras().getBoolean("use_vibrator"));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_options, menu);
        return true;
    }

    
    @Override
	public void onBackPressed() {
		// TODO Auto-generated method stub
		
		Intent resultData = new Intent();
		
		resultData.putExtra("use_target_sounds", cbUseTargetSounds.isChecked());
		resultData.putExtra("use_stone_sounds", cbUseStoneSounds.isChecked());
		resultData.putExtra("use_vibrator", cbUseVibrator.isChecked());
		setResult(RESULT_OK, resultData);
		super.onBackPressed();
	}

	@Override
	protected void onStop() {		
		super.onStop();
		
	}

	@Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                NavUtils.navigateUpFromSameTask(this);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

}

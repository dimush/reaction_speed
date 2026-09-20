// Properties (on Project) -> Android Tools -> Rename Application Package
package org.softosaurus.reactionspeed;

import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;

public class ReactionSpeedActivity extends Activity {
	private static final int SHOW_OPTIONS = 30960;
	public static boolean RELEASE = false;
	private MySurfaceView msv;
	AdView mAdView;
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, 
                                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.main);
        msv = (MySurfaceView)this.findViewById(R.id.mySurfaceView1);
        Thread thr = new Thread(msv);
        thr.start();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        SharedPreferences settings = this.getPreferences(0);
        msv.best_res_size = settings.getInt("best_res_size", 0);
        for(int i = 0; i < msv.best_res_size; i++) {
        	msv.best_res[i] = settings.getInt("best_res" + i, 0);
        }
        msv.results_vector.clear();
        int res_vec_size = settings.getInt("res_size", 0);
        for(int i = 0; i < res_vec_size; i++) {
        	msv.results_vector.add(settings.getInt("res" + i, 0));
        }
        msv.useStoneSounds = settings.getBoolean("use_stone_sounds", true);
        msv.useTargetSounds = settings.getBoolean("use_target_sounds", true);
        msv.useVibrator = settings.getBoolean("use_vibrator", true);
        if(!RELEASE) {
			MobileAds.initialize(this, new OnInitializationCompleteListener() {
				@Override
				public void onInitializationComplete(InitializationStatus initializationStatus) {
				}
			});
			mAdView = new AdView(this);
			mAdView.setAdSize(AdSize.SMART_BANNER);
			LinearLayout bannerLayout = (LinearLayout)findViewById(R.id.linearLayout1);
			// Test banner
			//mAdView.setAdUnitId("ca-app-pub-3940256099942544/6300978111");
			mAdView.setAdUnitId("ca-app-pub-1665272374483034/3757059300");
			// Add the adView to it
	        bannerLayout.addView(mAdView);
			AdRequest adRequest = new AdRequest.Builder().build();
			mAdView.loadAd(adRequest);
        }
    }

	@Override
	protected void onPause() {
		// TODO Auto-generated method stub
		super.onPause();
		SharedPreferences settings = this.getPreferences(0);
		SharedPreferences.Editor eset = settings.edit();		
		eset.putInt("best_res_size", msv.best_res_size);	
		for(int i = 0; i < msv.best_res_size; i++) {
			eset.putInt("best_res" + i, msv.best_res[i]);
        }
		eset.putInt("res_size", msv.results_vector.size());	
		for(int i = 0; i < msv.results_vector.size(); i++) {
			eset.putInt("res" + i, (Integer)msv.results_vector.elementAt(i));
        }
		eset.putBoolean("use_stone_sounds", msv.useStoneSounds);
		eset.putBoolean("use_target_sounds", msv.useTargetSounds);
		eset.putBoolean("use_vibrator", msv.useVibrator);
		eset.commit();
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater mi = getMenuInflater();
		mi.inflate(R.menu.menu, menu);
		return true;
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		// TODO Auto-generated method stub
		//super.onActivityResult(requestCode, resultCode, data);
		switch(requestCode) {
			case SHOW_OPTIONS: {
				if(resultCode == RESULT_OK) {
					msv.useTargetSounds = data.getExtras().getBoolean("use_target_sounds");
					msv.useStoneSounds = data.getExtras().getBoolean("use_stone_sounds");
					msv.useVibrator = data.getExtras().getBoolean("use_vibrator");
				}
				break;
			}
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.cleanHistItem) {
			msv.clearBestResults();
			if(msv.getTop10_state() == 2) {
				msv.setTop10_state(3);
			}
			return true;
		} else if (id == R.id.optionsItem) {
			Intent intent = new Intent(this, OptionsActivity.class);
			intent.putExtra("use_target_sounds", msv.useTargetSounds);
			intent.putExtra("use_stone_sounds", msv.useStoneSounds);
			intent.putExtra("use_vibrator", msv.useVibrator);
			this.startActivityForResult(intent, SHOW_OPTIONS);
			return true;
		} else if (id == R.id.buyItem) {
			Intent intent = new Intent(Intent.ACTION_VIEW);
			intent.setData(Uri.parse("market://details?id=org.softosaurus.reactionspeedpro"));
			startActivity(intent);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}
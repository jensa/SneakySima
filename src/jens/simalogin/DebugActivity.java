package jens.simalogin;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class DebugActivity  extends Activity {


	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.debug);
		Bundle debugTextB = getIntent().getExtras();
		String debugText = debugTextB.getString("DEBUG");
		createView(debugText);
		
	}
	
	private void createView(String t){
		TextView tV = (TextView) findViewById(R.id.debugWindow);
		tV.setText(t);
	}

}

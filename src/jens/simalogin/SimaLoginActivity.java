package jens.simalogin;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;

import org.apache.oro.text.regex.MalformedPatternException;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import expect4j.Closure;
import expect4j.Expect4j;
import expect4j.ExpectState;
import expect4j.matches.Match;
import expect4j.matches.RegExpMatch;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.Toast;


public class SimaLoginActivity extends Activity {

	static int SSH_PORT = 22;
	private static String[] linuxPromptRegEx = new String[]{"\\>","#", "~#","~$","~>"};
	static String ENTER_CHAR = "\r";
	private final String LOGOUT_CHAR = "u";
	private String BASE_COURSES = "adk#algokomp#allmanhand#appcs#automat#bik#bildat#bioinfo#cprog#dasak#datae#dbkart#dbt#dbtinf#dgi#digpub#fovgrund#fovnet#fovopad#graftek#grip#grudat_F3#gruint#grupdat_F1#inda#ingdbt#ingprog#intnet#intro#j2ee#labbvecka#logik#maskin#medpro#numbio#numd#nume#numf#numi#numo#numpbd#numpk#numpm#numpp#numpt#nums#numt#oopk#peer#prgbio#prgcl#prgi#prgm#prgmed#prgo#prgt#prgv#progk#progp#prost#prutt#semantik#suda1#suoop1#sysprog#tilda#tilnum1#tilprog#xmlpub#";
	private final int COURSE_FILE_BYTES = 1000;
	private static final int COMMAND_SUCCESS = -2;
	private boolean connected;
	private final String CONNECTION_FAIL = "Login misslyckades, testa 'Debug'-knappen, eller försök igen";
	
	private Expect4j expect;

	private Intent intent;
	private ProgressDialog connDia;

	private List<String> courses;
	private StringBuilder buffer;
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		FileInputStream courseData = null;
		try{
			courseData = openFileInput("courses.dat");

		} catch (IOException e) {
			// Happens on first startup, create the course list
			createCourseFile();
			try {
				courseData = openFileInput("courses.dat");
			} catch (FileNotFoundException e1) {
				Toast.makeText(this, "Kunde inte skriva till fil,kurslistan ej initialiserad", Toast.LENGTH_LONG);
			}

		}
		List<String> c = makeCourseList(courseData);
		courses = c;
		setContentView(R.layout.main);
		buffer = new StringBuilder();
		intent = new Intent(SimaLoginActivity.this,DebugActivity.class);
		createView();

	}
	@Override
	public void onPause(){
		super.onPause();
		String[] data = getConnData();
		data[1] = ""; // don't write password
		StringBuilder state = new StringBuilder();
		for(String s : data){
			state.append(s);
			state.append("#");
		}
		saveState(state.toString());
	}
	@Override
	public void onResume(){
		super.onResume();
		String data = readState();
		if(data != null && !data.equals("")){
			setupFields(data);
		}

	}
	@Override
	public void onDestroy(){
		super.onDestroy();
		closeConnection(true);
	}

	public void createView(){
		Spinner courseS = (Spinner) findViewById(R.id.coursef);
		SpinnerAdapter adapter = new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,courses);
		courseS.setPrompt("Välj kurs");
		courseS.setAdapter(adapter);
		Button connB = (Button) findViewById(R.id.connectb);
		connB.setOnClickListener(new OnClickListener(){

			@Override
			public void onClick(View v) {
				if(!connected){
				String[] d = getConnData();
				new LoginTask().execute(d);
				connDia = new ProgressDialog(SimaLoginActivity.this);
				connDia.setMessage("Ansluter, häng kvar...");
				connDia.show();
				}else{
					Toast.makeText(v.getContext(), "Redan i en sima-k�!", Toast.LENGTH_LONG);
				}
			}

		});
		Button disC = (Button) findViewById(R.id.disconnectb);
		disC.setOnClickListener(new OnClickListener(){

			@Override
			public void onClick(View v) {
				closeConnection(false);
			}

		});
		Button debug = (Button) findViewById(R.id.debugB);
		debug.setOnClickListener(new OnClickListener(){

			@Override
			public void onClick(View v) {
				Bundle b = new Bundle();
				String debugInfo = buffer.toString();
				if(debugInfo.length()>500){
					debugInfo = debugInfo.substring(250);
				}
				b.putString("DEBUG", debugInfo);
				intent.putExtras(b);
				v.getContext().startActivity(intent);
			}

		});
		EditText hostf = (EditText) findViewById(R.id.hostf);
		hostf.setText("my.nada.kth.se");


	}

	public boolean sendCommands(List<String> cmds){

		Closure closure = new Closure(){
			public void run(ExpectState expectState) throws Exception{
				buffer.append(expectState.getBuffer());
			}
		};
		List<Match> lstPattern =  new ArrayList<Match>();
		for (String regexElement : linuxPromptRegEx) {
			// add 'bash symbols' to lstPattern
			try {
				Match mat = new RegExpMatch(regexElement, closure);
				lstPattern.add(mat);
			} catch(Exception e) {
				e.printStackTrace();
			}
		}
		try{
			boolean isSuccess = true;
			for(String cmd : cmds){
				isSuccess = isSuccess(lstPattern,cmd);
				if(!isSuccess){
					sendEnterBuffers(lstPattern);
					isSuccess = isSuccess(lstPattern,cmd);
					if(!isSuccess){
						return false;
					}
				}
			}
		}catch(Exception e){
			Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
		}
		return true;

	}

	//Send 30 Enter presses to the terminal, to page through a possible admin message(They're never informative anyway:)
	private void sendEnterBuffers(List<Match> lst) {
		//StringBuilder enters = new StringBuilder();
		try {
			for(int i=0;i<30;i++){
				//enters.append(ENTER_CHAR);
				expect.send(ENTER_CHAR);
			} 
		}catch (IOException e) {
			Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
		}


	}

	private void closeConnection(boolean destroying) {
		if(expect != null){
			ArrayList<String> close = new ArrayList<String>();
			close.add(LOGOUT_CHAR);
			sendCommands(close);
			expect.close();
			expect = null;
			connected = false;
			Toast.makeText(this, "Lämnat kön", Toast.LENGTH_LONG).show();
		}else{
			if(!destroying){
			Toast.makeText(this, "Inte uppkopplad!", Toast.LENGTH_LONG).show();
			}
		}

	}


	private boolean isSuccess(List<Match> objPattern,String stringToSend){
		try {
			boolean isFailed = checkResult(expect.expect(objPattern));

			if (!isFailed) {
				expect.send(stringToSend);
				expect.send(ENTER_CHAR);
				return true;
			}
			else{
				return false;
			}

		} catch (MalformedPatternException ex) {
			ex.printStackTrace();
			return false;
		} catch (Exception ex) {
			ex.printStackTrace();
			return false;
		}
	}
	
	private boolean checkResult(int intRetVal) {
		if (intRetVal == COMMAND_SUCCESS) {
			return true;
		}
		return false;
	}


	public Expect4j connect(String user,String pass,String host){
		Session session = null;
		try {
			session = new JSch().getSession(user, host,SSH_PORT);
			Hashtable<String,String> config = new Hashtable<String,String>();
			config.put("StrictHostKeyChecking", "no");
			session.setConfig(config);
			session.setPassword(pass);
			session.connect(60000);
			ChannelShell channel = (ChannelShell) session.openChannel("shell");
			Expect4j expect = new Expect4j(channel.getInputStream(),channel.getOutputStream());
			channel.connect();
			return expect;
		}catch (JSchException e) {
			Toast.makeText(this, e.getMessage(),Toast.LENGTH_LONG).show();
			return null;
		} catch (IOException e) {
			Toast.makeText(this, e.getMessage(),Toast.LENGTH_LONG).show();
			e.printStackTrace();
			return null;
		} catch (Exception e) {
			Toast.makeText(this, e.getMessage(),Toast.LENGTH_LONG).show();
			return null;
		}
	}
	private class LoginTask extends AsyncTask<String,String,String>{

		@Override
		protected String doInBackground(String... d) {
			String usrName = d[0];String password = d[1];String hostn = d[2];String course = d[3];String msg= d[4];
			boolean isRed = ((CheckBox) findViewById(R.id.redCheck)).isChecked();
			if(!usrName.equals("") && !password.equals("") &&
					!course.equals("") &&!msg.equals("")){
				List<String> cmds = new ArrayList<String>();
				cmds.add("module add sima");
				cmds.add("st");
				String user = usrName;
				String pass = password;
				cmds.add(course);
				if(isRed){
					cmds.add("r "+msg);
				}else{
					cmds.add("h "+msg);
				}
				try {
					InetAddress[] hostAds = InetAddress.getAllByName(hostn);
					String host = hostAds[0].getHostAddress();
					expect = connect(user,pass,host);
					boolean loginSuccess = sendCommands(cmds);
					if(loginSuccess){
						scanForNewCourses();
						return "Inloggad på "+course+"!";
					}else{
						return CONNECTION_FAIL;
					}
				} catch (UnknownHostException e) {
					return "Unknown host";
				}

			}else{
				return "Fyll i alla fält!";
			}
		}

		@Override
		public void onPostExecute(String r){
			connDia.dismiss();
			Toast.makeText(SimaLoginActivity.this, r, Toast.LENGTH_LONG).show();
			if(r.equals(CONNECTION_FAIL)){
				connected = false;
			}else{
				connected = true;
			}
		}


	}
	private String[] getConnData(){
		String usrName = ((EditText) findViewById(R.id.usernamef)).getText().toString();
		String password = ((EditText) findViewById(R.id.passf)).getText().toString();
		String hostn = ((EditText) findViewById(R.id.hostf)).getText().toString();
		String course =(String) ((Spinner) findViewById(R.id.coursef)).getSelectedItem();
		String msg = ((EditText) findViewById(R.id.msgf)).getText().toString();
		String coursePos = Integer.toString(((Spinner) findViewById(R.id.coursef)).getSelectedItemPosition());
		return new String[]{usrName,password,hostn,course,msg,coursePos};
	}
	private void setupFields(String data){
		String[] fields = data.split("#");
		//fields[0] == username, [1] == password(n/a),[2] == hostname(not needed),[3]== course name(unused),[4] == message,[5] == course selection position
		((EditText) findViewById(R.id.usernamef)).setText(fields[0]);
		EditText hostf = (EditText) findViewById(R.id.hostf);
		hostf.setText("my.nada.kth.se");
		((Spinner) findViewById(R.id.coursef)).setSelection(Integer.parseInt(fields[5]));
		((EditText) findViewById(R.id.msgf)).setText(fields[4]);

	}
	
	
	/* IO methods */

	private List<String> makeCourseList(FileInputStream courseData) {
		if(courseData == null){
			return new ArrayList<String>();
		}
		InputStreamReader reader = new InputStreamReader(courseData);
		char[] buf = new char[COURSE_FILE_BYTES]; // this might have to be bigger if the number of courses exceeds around 100
		try {
			reader.read(buf);
		} catch (IOException e) {
			Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG);
		}
		String bufString = new String(buf);
		String[] courseArray = bufString.split("#");
		List<String> c = new ArrayList<String>(Arrays.asList(courseArray));
		c.remove(c.size()-1);
		return c;
	}
	
	

	private void createCourseFile() {
		try{
			FileOutputStream out = openFileOutput("courses.dat",MODE_PRIVATE);
			OutputStreamWriter writer = new OutputStreamWriter(out);
			writer.write(BASE_COURSES);
			writer.flush();
			writer.close();
			out.close();
		}catch(IOException e){
			Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG);
		}

	}
	//see if any new courses were added to the terminal list, and if so, add them to courses.dat
	private void scanForNewCourses() {
		//Find the course list in the returned buffer
		String buf = buffer.toString();
		buf = buf.substring(buf.indexOf("courses:")+8,buf.indexOf("Enter course number"));
		String[] courseRows = buf.split("\\n");
		//-2 because of two '\r'-chars(in beginning and end) that I'm too lazy to remove
		int numberOfCourses = courseRows.length-2;
		if(numberOfCourses>courses.size()){
			int numNewCourses = numberOfCourses-courses.size();
			int newCourseIndex = courseRows.length-numNewCourses-1;
			//write the new courses to file
			for(int i=newCourseIndex,j=0;j<numNewCourses;j++,i++){
				String[] courseBuf = courseRows[i].split("\\d*\\.\\s|\\r");
				String newCourseName = courseBuf[1];
				try{
					FileOutputStream out = openFileOutput("courses.dat",MODE_PRIVATE);
					OutputStreamWriter writer = new OutputStreamWriter(out);
					writer.append(newCourseName+"#");
					writer.flush();
					writer.close();
					out.close();
				}catch(IOException e){
				}
			}
		}


	}
	
	private String readState(){
		FileInputStream inS;
		InputStreamReader reader;

		try{
			inS = openFileInput("state.dat");
			reader = new InputStreamReader(inS);
			char[] stateDataBuf = new char[255];
			reader.read(stateDataBuf);
			String stateData = new String(stateDataBuf);
			return stateData;

		} catch (IOException e) {
			// Happens on first startup, do nothing
		}
		return null;
	}

	private void saveState(String state){
		FileOutputStream outS;
		OutputStreamWriter writer;

		try {
			outS = openFileOutput("state.dat",MODE_PRIVATE);
			writer = new OutputStreamWriter(outS);
			writer.write(state);
			writer.flush();
			writer.close();
			outS.close();
		} catch (IOException e) {
			Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
		}

	}
}
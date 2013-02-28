/*
 * PhoneGap is available under *either* the terms of the modified BSD license *or* the
 * MIT License (2008). See http://opensource.org/licenses/alphabetical for full text.
 *
 * Copyright (c) 2005-2010, Nitobi Software Inc.
 * Copyright (c) 2010, IBM Corporation
 */

package com.phonegap.plugin.sqlitePlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.Number;
import java.util.HashMap;
import java.util.regex.*;
import java.lang.StringBuilder;

import org.apache.cordova.api.CordovaPlugin;
import org.apache.cordova.api.CallbackContext;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.*;
import android.util.Log;

public class SQLitePlugin extends CordovaPlugin{

	HashMap<String, SQLiteDatabase> databaseMap;

	public SQLitePlugin() {
		databaseMap = new HashMap<String, SQLiteDatabase>();
	}

	@Override
	public boolean execute(String action, JSONArray arguments, final CallbackContext callbackContext) throws JSONException {

		if (action.equals("open")) {
			this.openDatabase(arguments.getJSONObject(0).getString("name"));
		} else if (action.equals("close")) {
			this.closeDatabase(arguments.getJSONObject(0).getString("name"));
		} else if (action.equals("executeSqlBatch")) {
			final String transactionId = arguments.getJSONArray(1).getJSONObject(0).getString("trans_id"); //TODO : check if transactionArguments.getJSONObject(0) is not null;
			if(transactionId == null) return false ;
       		class ExecuteSqlBatchThread implements Runnable {
			    SQLitePlugin self;
			    JSONArray    arguments;
			    ExecuteSqlBatchThread(SQLitePlugin _self, JSONArray _arguments) { self = _self;  arguments = _arguments; }
			    public void run(){
					try{
						String      databaseName   		 		= arguments.getString(0);
						JSONArray   transactionArguments 		= arguments.getJSONArray(1);
						int         transactionArgumentsLength 	= transactionArguments.length();
						String[] 	queries 	 		 		= new String[transactionArgumentsLength];
						String[] 	queryIDs 	 		 		= new String[transactionArgumentsLength];
						JSONArray[] jsonParameters 			 	= new JSONArray[transactionArgumentsLength];

						for (int i = 0; i < transactionArgumentsLength; i++) {
							JSONObject a  	  = transactionArguments.getJSONObject(i);
							queries[i] 	  	  = a.getString("query");
							queryIDs[i]       = a.getString("query_id");
							jsonParameters[i] = a.getJSONArray("params");
						}
			       		self.executeSqlBatch(databaseName, queries, jsonParameters, queryIDs, transactionId);
			       		System.gc();
			       	} catch (JSONException e){}
			    }
			}
	        cordova.getThreadPool().execute(new ExecuteSqlBatchThread(this, arguments));
		}
		return true;
	}

	@Override
	public void onDestroy() {
		while (!this.databaseMap.isEmpty()) {
			String databaseName = this.databaseMap.keySet().iterator().next();
			this.closeDatabase(databaseName);
			this.databaseMap.remove(databaseName);
		}
	}

	private void openDatabase(String databaseName){
		if (this.databaseMap.get(databaseName) != null) return ;

		File 		   dbfile 	= this.cordova.getActivity().getDatabasePath(databaseName + ".db");
		SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(dbfile, null);

		databaseMap.put(databaseName, database);
	}

	private void closeDatabase(String databaseName){
		SQLiteDatabase database = this.databaseMap.get(databaseName);
		if (database == null) return ;
		database.close();
		this.databaseMap.remove(databaseName);
	}

	private void executeSqlBatch(String databaseName, String[] queries, JSONArray[] jsonParameters, String[] queryIDs, String transactionId){
		SQLiteDatabase database = this.databaseMap.get(databaseName);

		if (database == null) return;
		long begin = System.currentTimeMillis();

		try {
			database.beginTransaction();

			String query   = "" ;
			String queryId = "" ;

			for (int i = 0; i < queries.length; i++) {
				query = queries[i];
				queryId = queryIDs[i];
				if (query.toLowerCase().startsWith("insert") && jsonParameters != null) {
					SQLiteStatement statement = database.compileStatement(query);
					for (int j = 0; j < jsonParameters[i].length(); j++) {
						if (jsonParameters[i].get(j) instanceof Float || jsonParameters[i].get(j) instanceof Double ) {
							statement.bindDouble(j + 1, jsonParameters[i].getDouble(j));
						} else if (jsonParameters[i].get(j) instanceof Number) {
							statement.bindLong(j + 1, jsonParameters[i].getLong(j));
						} else {
							statement.bindString(j + 1, jsonParameters[i].getString(j));
						}
					}
					long insertId = statement.executeInsert();
					this.webView.sendJavascript("window.SQLitePluginTransactionCB.queryCompleteCallback('" + transactionId + "','" + queryId + "', {'insertId':'" + insertId + "'});");
				} else {
					String[] parameters = new String[jsonParameters[i].length()];
					for (int j = 0; j < jsonParameters[i].length(); j++) {
						if (jsonParameters[i].isNull(j))
							parameters[j] = "";
						else
							parameters[j] = jsonParameters[i].getString(j);
					}
					Cursor cursor = database.rawQuery(query, parameters);
					if(queryId.length() > 0){
						this.webView.sendJavascript("window.SQLitePluginTransactionCB.queryCompleteCallback('" +transactionId + "','" + queryId + "', " + this.resultsToString(cursor) + ");");
					}
					cursor.close();
				}
			}
			database.setTransactionSuccessful();
		} catch (Exception ex) {
			database.endTransaction();
			Log.d("CordovaLog/SQLitePlugin", "SQLitePlugin.executeSql(): Error = " + ex.getMessage());
			this.webView.sendJavascript("window.SQLitePluginTransactionCB.txErrorCallback('" + transactionId + "', '"+ex.getMessage()+"');");
		} finally {
			database.endTransaction();
			this.webView.sendJavascript("window.SQLitePluginTransactionCB.txCompleteCallback('" + transactionId + "');");
		}

	}

	private String resultsToString(Cursor cursor){
		if (!cursor.moveToFirst() || cursor.getColumnCount() < 1) {return "[]";}

		Pattern       p 	 	 = Pattern.compile("[']+");
		StringBuilder result 	 = new StringBuilder(65536);
		int 		  colCount   = cursor.getColumnCount();
		int 		  colTypes[] = new int[colCount];
		String 	   	  colNames[] = new String[colCount];
		
		for (int i = 0; i < colCount; ++i) {
			colNames[i] = cursor.getColumnName(i);
			colTypes[i] = cursor.getType(i);
		}

		result.append("[");
		do {
			result.append("{");
			for (int i = 0; i < colCount; ++i) {
				switch(colTypes[i]){
					case Cursor.FIELD_TYPE_NULL:
						result.append(colNames[i]).append(":null,");
						break;
					case Cursor.FIELD_TYPE_INTEGER:
						result.append(colNames[i]).append(":").append(cursor.getInt(i)).append(",");
						break;
					case Cursor.FIELD_TYPE_FLOAT:
						result.append(colNames[i]).append(":").append(cursor.getFloat(i)).append(",");
						break;
					case Cursor.FIELD_TYPE_BLOB:
						result.append(colNames[i]).append(":'").append(cursor.getBlob(i)).append("',");
						break;
					case Cursor.FIELD_TYPE_STRING:
						String val = cursor.getString(i);
						if(val.indexOf("'") >= 0) val = p.matcher(val).replaceAll("\\\\'");
						result.append(colNames[i]).append(":'").append(val).append("',");
						break;
				}
			}			
			result.deleteCharAt(result.length()-1);
			result.append("},");
		} while (cursor.moveToNext());
		return result.deleteCharAt(result.length()-1).append("]").toString();
	}
}
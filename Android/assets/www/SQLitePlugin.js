/*global cordova, console */
(function () {
  // 'use strict';

  var get_unique_id = function () {
    var  id = new Date().getTime(),
      _id = new Date().getTime();

    while (id === _id) {
      _id = new Date().getTime();
    }
    return _id + "000";
  };

  var root                       = this,
    SQLiteFactory              = {},
    SQLitePluginTransaction    = {},
    SQLitePluginTransactionCB  = {},
    transaction_callback_queue = {},
    transaction_queue          = [];

  var SQLitePluginCallback = function (result) {
    var myCallback = function () {
      return 1;
    };
    myCallback(result);
  };

  var SQLitePlugin = function (openArguments, openSuccess, openError) {

    if (!(openArguments)) {
      throw new Error("Cannot create a SQLitePlugin instance without arguments");
    }

    if (!(openArguments.name)) {
      throw new Error("Cannot create a SQLitePlugin instance without a database name");
    }

    this.openArguments = openArguments;
    this.dbname        = openArguments.name;
    this.openSuccess   = this.openSuccess || function ( ) { console.log("SQLite database opened: " + openArguments.name); };
    this.openError     = this.openError   || function (e) { console.log("SQLite database error: "  + e.message); };

    this.open(this.openSuccess, this.openError);
  };

  SQLitePlugin.prototype._openDatabases = {};

  SQLitePlugin.prototype.transaction = function (fn, error, success) {
    var transaction;
    transaction = new SQLitePluginTransaction(this.dbname);
    fn(transaction);
    transaction.complete(success, error);
  };

  SQLitePlugin.prototype.open = function (successCallback, errorCallback) {
    try{
      if (!(this.dbname in this._openDatabases)) {
        this._openDatabases[this.dbname] = true;
        cordova.exec(successCallback, errorCallback, "SQLitePlugin", "open", [this.openArguments]);
      }
    } catch(e){
      console.log("Exception while using cordova.exec function open()");
    }
  };

  SQLitePlugin.prototype.close = function () {
    if (this.dbname in this._openDatabases) {
      delete this._openDatabases[this.dbname];
      try{
        cordova.exec(null, null, "SQLitePlugin", "close", [this.dbname]);
      } catch(e){
        console.log("Exception while using cordova.exec function close()");
      }
    }
  };

  SQLitePluginTransaction = function (dbname) {
    this.dbname      = dbname;
    this.executes    = [];
    this.trans_id    = get_unique_id();
    this.__completed = false;
    this.__submitted = false;
    this.optimization_no_nested_callbacks     = false;
    transaction_queue[this.trans_id]          = [];
    transaction_callback_queue[this.trans_id] = {};
  };

  SQLitePluginTransactionCB.queryCompleteCallback = function (transaction_id, queryId, result) {
    var query = null,
      transaction;

    for (transaction in transaction_queue[transaction_id]) {
      if (transaction_queue[transaction_id][transaction].query_id === queryId) {
        query = transaction_queue[transaction_id][transaction];
        if (transaction_queue[transaction_id].length === 1) {
          transaction_queue[transaction_id] = [];
        } else {
          transaction_queue[transaction_id].splice(transaction, 1);
        }
        break;
      }
    }
    if (query && query.callback) return query.callback(result);
  };

  SQLitePluginTransactionCB.queryErrorCallback = function (transId, queryId, result) {
    var query, x;
    query = null;
    for (x in transaction_queue[transId]) {
      if (transaction_queue[transId][x].query_id === queryId) {
        query = transaction_queue[transId][x];
        if (transaction_queue[transId].length === 1) {
          transaction_queue[transId] = [];
        } else {
          transaction_queue[transId].splice(x, 1);
        }
        break;
      }
    }
    if (query && query.err_callback) return query.err_callback(result);
  };

  SQLitePluginTransactionCB.txCompleteCallback = function (transId) {
    if (typeof transId !== "undefined") {
      if (transId && transaction_callback_queue[transId] && transaction_callback_queue[transId].success) {
        return transaction_callback_queue[transId].success();
      }
    }
  };

  SQLitePluginTransactionCB.txErrorCallback = function (transId, error) {
    if (typeof transId !== "undefined") {
      if (transId && transaction_callback_queue[transId].error) {
        transaction_callback_queue[transId].error(error);
      }
      delete transaction_queue[transId];
      return delete transaction_callback_queue[transId];
    }
  };


  SQLitePluginTransaction.prototype.add_to_transaction = function (trans_id, query, params, callback, err_callback) {
    var new_query = {};
    new_query.trans_id = trans_id;
    if (callback || !this.optimization_no_nested_callbacks) {
      new_query.query_id = get_unique_id();
    } else {
      if (this.optimization_no_nested_callbacks) new_query.query_id = "";
    }
    new_query.query = query;
    if (params) {
      new_query.params = params;
    } else {
      new_query.params = [];
    }
    new_query.callback = callback;
    new_query.err_callback = err_callback;
    if (!transaction_queue[trans_id]) transaction_queue[trans_id] = [];
    transaction_queue[trans_id].push(new_query);
  };

  SQLitePluginTransaction.prototype.executeSql = function (sql, values, success, error) {

    var transaction       = this ;

    var successCallback   = success ? function (execres) {
        var saveres = execres,
          resultSet = {
            rows: {
              item: function (i) {
                return saveres[i];
              },
              length: saveres.length
            },
            rowsAffected : saveres.rowsAffected,
            insertId     : saveres.insertId || null
          };
        return success(transaction, resultSet);
      } : null ;

    var errorCallback     = success ? function (resultSet) {
        return error(transaction, resultSet);
      } : null ;

    this.add_to_transaction(this.trans_id, sql, values, successCallback, errorCallback);
  };

  SQLitePluginTransaction.prototype.complete = function (successCallback , errorCallback) {
    var transaction = this;

    if(transaction.__completed){
      throw new Error("Transaction already run");
    }

    if(transaction.__submitted){
      throw new Error("Transaction already submitted");
    }

    transaction.__submitted = true;

    transaction_callback_queue[transaction.trans_id].successCallback = successCallback ? function () {
        if (transaction_queue[transaction.trans_id].length > 0 && !transaction.optimization_no_nested_callbacks) {
          transaction.__submitted = false;
          return transaction.complete(successCallback, errorCallback);
        } else {
          transaction.__completed = true;
          if (successCallback){
            return successCallback(transaction);
          }
        }
      } : null ;

    transaction_callback_queue[transaction.trans_id].errorCallback   = errorCallback ? function (res) {
        return errorCallback(transaction, res);
      } : null ;

    try{
      cordova.exec(null, null, "SQLitePlugin", "executeSqlBatch", [transaction.dbname, transaction_queue[transaction.trans_id]]);
    } catch(e){
      console.log("Exception while using cordova.exec function executeSqlBatch()");
    }

  };

  root.SQLitePluginCallback      = SQLitePluginCallback;
  root.SQLitePluginTransactionCB = SQLitePluginTransactionCB;
  root.sqlitePlugin              = {};
  root.sqlitePlugin.openDatabase = function(openArguments, successCallback, errorCallback) {
    return new SQLitePlugin(openArguments, successCallback, errorCallback);
  };

  return root.sqlitePlugin;

}());
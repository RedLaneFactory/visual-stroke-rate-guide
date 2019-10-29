package svenmeier.coxswain.google;

import android.app.Activity;
import android.content.Context;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.data.DataSet;
import com.google.android.gms.fitness.data.Session;
import com.google.android.gms.fitness.request.SessionInsertRequest;

import java.util.List;
import java.util.concurrent.TimeUnit;

import svenmeier.coxswain.Coxswain;
import svenmeier.coxswain.Gym;
import svenmeier.coxswain.R;
import svenmeier.coxswain.gym.Snapshot;
import svenmeier.coxswain.gym.Workout;
import svenmeier.coxswain.io.Export;

/**
 */
public class FitExport extends Export<Workout> {

	private Handler handler = new Handler();

	private final Gym gym;

	private Workout workout;

	private Connection connection;

	public FitExport(Context context) {
		super(context);

		this.handler = new Handler();

		this.gym = Gym.instance(this.context);
	}

	@Override
	public void start(Workout workout, boolean automatic) {
		this.workout = workout;

		connection = new Connection();
	}

	private void toast(final String text) {
		handler.post(new Runnable() {
			@Override
			public void run() {
				Toast.makeText(context, text, Toast.LENGTH_LONG).show();
			}
		});
	}

	private class Connection implements GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks, Runnable {

		private final GoogleApiClient client;

		private final int REQUEST_CODE = 1;

		public Connection() {
			client = new GoogleApiClient.Builder(context)
					.addApi(Fitness.SESSIONS_API)
					.addApi(Fitness.HISTORY_API)
					.addScope(new Scope(Scopes.FITNESS_ACTIVITY_READ_WRITE))
					.addScope(new Scope(Scopes.FITNESS_BODY_READ_WRITE))
					.addScope(new Scope(Scopes.FITNESS_LOCATION_READ_WRITE))
					.addConnectionCallbacks(this)
					.addOnConnectionFailedListener(this)
					.build();

			client.connect();
		}

		@Override
		public void onConnectionFailed(ConnectionResult result) {
			if (result.hasResolution() && context instanceof Activity) {
				try {
					result.startResolutionForResult((Activity) context, REQUEST_CODE);
					return;
				} catch (IntentSender.SendIntentException e) {
					Log.e(Coxswain.TAG, "start resolution failed", e);
				}
			}

			toast(context.getString(R.string.googlefit_export_failed));
		}

		/**
		 * TODO activity should delegate results
		 */
		public void onResult(int requestCode, int resultCode) {
			if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK) {
				if (client.isConnecting() == false && client.isConnected() == false) {
					client.connect();
				}
			}
		}

		@Override
		public void onConnected(Bundle bundle) {
			new Thread(this).start();
		}

		@Override
		public void onConnectionSuspended(int i) {
		}

		@Override
		public void run() {
			toast(context.getString(R.string.googlefit_export_starting));
			Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

			List<Snapshot> snapshots = gym.getSnapshots(workout).list();
			try {
				Workout2Fit workout2Fit = new Workout2Fit();

				Status status;
				String rejected = null;

				Session session = workout2Fit.session(workout);
				SessionInsertRequest insertSession = new SessionInsertRequest.Builder()
						.setSession(session)
						.build();
				status = Fitness.SessionsApi.insertSession(client, insertSession).await(1, TimeUnit.MINUTES);
				if (status.isSuccess() == false) {
					Log.e(Coxswain.TAG, "insert session rejected " + status);
					rejected = "SESSION";
				} else {
					for (DataSet dataSet : workout2Fit.dataSets(workout, snapshots)) {
						status = Fitness.HistoryApi.insertData(client, dataSet).await(1, TimeUnit.MINUTES);
						if (status.isSuccess() == false) {
							Log.e(Coxswain.TAG, "insert dataset rejected " + status);
							rejected = dataSet.getDataType().getName();
						}
					}
				}

				if (rejected == null) {
					toast(context.getString(R.string.googlefit_export_finished));
				} else {
					toast(context.getString(R.string.googlefit_export_rejected, rejected));
				}
			} catch (Exception ex) {
				toast(context.getString(R.string.googlefit_export_failed));
			} finally {
				snapshots.clear();

				client.disconnect();
			}
		}
	}
}
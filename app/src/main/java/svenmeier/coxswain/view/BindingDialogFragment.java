package svenmeier.coxswain.view;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;

import java.util.Arrays;
import java.util.List;

import svenmeier.coxswain.BuildConfig;
import svenmeier.coxswain.R;

public class BindingDialogFragment extends DialogFragment {

	public static final String BINDING_INDEX = "id";

	public static final String BINDING = "binding";

	private static final List<ValueBinding> bindings = Arrays.asList(
			ValueBinding.DISTANCE,
			ValueBinding.DURATION,
			ValueBinding.STROKES,
			ValueBinding.ENERGY,
			ValueBinding.SPEED,
			ValueBinding.PULSE,
			ValueBinding.STROKE_RATE,
			ValueBinding.STROKE_RATIO,
			ValueBinding.POWER,
			ValueBinding.TIME,
			ValueBinding.SPLIT,
			ValueBinding.AVERAGE_SPLIT,
			ValueBinding.DELTA_DISTANCE,
			ValueBinding.DELTA_DURATION);

	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {

		final int index = getArguments().getInt(BINDING_INDEX);
		final ValueBinding binding = (ValueBinding) getArguments().getSerializable(BINDING);

		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());

		final String[] items = new String[bindings.size()];
		for (int b = 0; b < bindings.size(); b++) {
			items[b] = getString(bindings.get(b).label);
		}

		builder.setTitle(R.string.action_bind);
		builder.setSingleChoiceItems(items, bindings.indexOf(binding), new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				ValueBinding binding = bindings.get(which);

				getCallback().onBinding(index, binding);

				dismiss();
			}
		});
		builder.setNegativeButton(R.string.workout_binding_decrease, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				getCallback().onDecrease(index);
			}
		});
		builder.setPositiveButton(R.string.workout_binding_increase, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				getCallback().onIncrease(index);
			}
		});

		return builder.create();
	}

	private Callback getCallback() {
		return (Callback)getActivity();
	}

	@Override
	public void onDismiss(DialogInterface dialog) {
		final int index = getArguments().getInt(BINDING_INDEX);

		if (isAdded()) {
			(getCallback()).onBinding(index, null);
		}

		super.onDismiss(dialog);
	}

	public static BindingDialogFragment create(int index, ValueBinding binding) {
		BindingDialogFragment fragment = new BindingDialogFragment();

		Bundle argumets = new Bundle();
		argumets.putInt(BINDING_INDEX, index);
		argumets.putSerializable(BINDING, binding);
		fragment.setArguments(argumets);

		return fragment;
	}

	public interface Callback {
		void onBinding(int index, ValueBinding binding);

		void onDecrease(int index);

		void onIncrease(int index);
	}
}

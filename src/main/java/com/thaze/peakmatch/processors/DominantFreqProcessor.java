package com.thaze.peakmatch.processors;

import com.google.common.collect.Lists;
import com.thaze.peakmatch.EventProcessorConf;
import com.thaze.peakmatch.Util;
import com.thaze.peakmatch.XCorrProcessor;
import com.thaze.peakmatch.event.Event;
import com.thaze.peakmatch.event.EventException;
import org.apache.commons.math.complex.Complex;
import org.apache.commons.math.stat.descriptive.SummaryStatistics;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author srodgers
 *         created: 14/06/13
 */
public class DominantFreqProcessor implements Processor {



	private final EventProcessorConf _conf;

	public DominantFreqProcessor(EventProcessorConf conf) throws EventException {
		_conf = conf;
	}



	@Override
	public void process() throws EventException {

		System.out.println("dominant frequency mode");

		try (final BufferedWriter bw = new BufferedWriter(new FileWriter(XCorrProcessor.XCORR_DOMINANTFREQ_FILE)) ){

			// don't need to load all into memory
			Util.executePerEvent(_conf, new Util.EventAction() {
				@Override
				public void run(Event e) throws EventException {
					handleEvent(_conf, e, bw);
				}
			});


		} catch (IOException e) {
			System.err.println("error writing file " + XCorrProcessor.XCORR_DOMINANTFREQ_FILE);
			throw new EventException(e);
		}
	}

	public static void handleEvent(final EventProcessorConf conf, Event e, Writer writer ) throws EventException {

		class Freq implements Comparable<Freq> {

			final double _abs;
			final double _frequency;

			Freq(int index, double abs, int sampleCount){
				_abs=abs;
				_frequency = Util.frequencyFromFFTPosition(index, conf.getDominantFreqSampleRate(), sampleCount);
			}

			@Override
			public int compareTo(Freq o) {
				return Double.compare(o._abs, _abs);
			}
		}

		double[] d = e.getD();

		// zero pad to next power of two
		int len = Util.nextPowerOfTwo(d.length * 2);
		d = Arrays.copyOf(d, len);

		Complex[] cs = Util.FFTtransform(d);
		cs = Arrays.copyOf(cs, cs.length / 2); // second half is an inverted artifact of the transform, throw it away

		List<Freq> freqs = Lists.newArrayList();

		double filterBelowIndex = d.length / conf.getDominantFreqSampleRate() * conf.getDominantFreqFilterBelowHz();
		double filterAboveIndex = d.length / conf.getDominantFreqSampleRate() * conf.getDominantFreqFilterAboveHz();

		SummaryStatistics stats = new SummaryStatistics();
		for (int ii = (int) filterBelowIndex; ii < Math.min(cs.length, (int) filterAboveIndex); ii++) {

			double abs = cs[ii].abs();
			stats.addValue(abs);
			freqs.add(new Freq(ii, abs, d.length));
		}

		Collections.sort(freqs);
		List<Freq> topFreqs = Lists.newArrayList();
		outer:
		for (Freq f : freqs) {

			for (Freq alreadyGot : topFreqs) {
				if (Math.abs(f._frequency - alreadyGot._frequency) < conf.getDominantFreqBandWidth())
					continue outer;
			}

			topFreqs.add(f);

			if (topFreqs.size() == conf.getDominantFreqTopFreqCount())
				break;
		}

		List<Double> bandMeanAmps = Lists.newArrayList();

		if (null != conf.getMeanFrequencyAmplitudeBands()) {
			String[] bands = conf.getMeanFrequencyAmplitudeBands().replaceAll("[\\[\\]()]", "").split(" ");
			for (String band : bands) {
				String[] boundaries = band.split("-");
				if (boundaries.length != 2)
					throw new EventException("invalid dominantfreq.mean-frequency-amplitude-bands '" + conf.getMeanFrequencyAmplitudeBands() + "' - expecting hz ranges eg [1.5-5] [5-7.8]");

				try {
					double startIndex = d.length / conf.getDominantFreqSampleRate() * Double.parseDouble(boundaries[0]);
					double endIndex = d.length / conf.getDominantFreqSampleRate() * Double.parseDouble(boundaries[1]);

					double total = 0;
					int count = 0;
					for (int ii = (int) startIndex; ii < Math.min(cs.length, (int) endIndex); ii++) {
						total += cs[ii].abs();
						count++;
					}

					bandMeanAmps.add(total / count);

				} catch (NumberFormatException nfe) {
					throw new EventException("invalid number format in dominantfreq.mean-frequency-amplitude-bands '" + conf.getMeanFrequencyAmplitudeBands() + "' - expecting hz ranges eg [1.5-5] [5-7.8]");
				}
			}
		}

		try {
			writer.write(e.getName());

			for (Freq f : topFreqs)
				writer.write("\t" + Util.NF.format(f._frequency));

			writer.write("\t" + Util.NF.format(e.getPeakAmp()));

			writer.write("\t" + Util.NF.format(stats.getStandardDeviation()));

			for (Double meanAmp : bandMeanAmps)
				writer.write("\t" + Util.NF.format(meanAmp));

			writer.write("\n");

		} catch (IOException e1) {
			throw new EventException("failed to write: " + e1);
		}
	}
}

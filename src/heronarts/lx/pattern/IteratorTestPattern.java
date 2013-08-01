/**
 * ##library.name##
 * ##library.sentence##
 * ##library.url##
 *
 * Copyright ##copyright## ##author##
 * All Rights Reserved
 *
 * @author      ##author##
 * @modified    ##date##
 * @version     ##library.prettyVersion## (##library.version##)
 */

package heronarts.lx.pattern;

import heronarts.lx.HeronLX;
import heronarts.lx.modulator.SawLFO;

/**
 * Braindead simple test pattern that iterates through all the nodes turning
 * them on one by one in fixed order.
 */
public class IteratorTestPattern extends LXPattern {

	final private SawLFO index;

	public IteratorTestPattern(HeronLX lx) {
		super(lx);
		this.addModulator(this.index = new SawLFO(0, lx.width, lx.width * 100)).trigger();
	}

	public void run(int deltaMs) {
		int active = (int) Math.floor(this.index.getValue());
		for (int i = 0; i < this.lx.width; ++i) {
//			this.colors[i] = (i == active) ? 0xFFFFFFFF : 0xFF000000;
			this.setColor(i, i, (i == active) ? 0xFFFFFFFF : 0xFF000000);
		}
	}
}

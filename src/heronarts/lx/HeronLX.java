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

package heronarts.lx;

import heronarts.lx.client.ClientListener;
import heronarts.lx.dmx.DMXOutput;
import heronarts.lx.effect.DesaturationEffect;
import heronarts.lx.effect.FlashEffect;
import heronarts.lx.effect.LXEffect;
import heronarts.lx.kinet.Kinet;
import heronarts.lx.kinet.KinetNode;
import heronarts.lx.modulator.LXModulator;
import heronarts.lx.modulator.LinearEnvelope;
import heronarts.lx.modulator.SawLFO;
import heronarts.lx.modulator.TriangleLFO;
import heronarts.lx.pattern.LXPattern;
import heronarts.lx.transition.LXTransition;

import java.awt.event.KeyEvent;
import java.net.SocketException;
import java.util.List;

import processing.core.PApplet;
import processing.core.PConstants;
import processing.core.PGraphics;

import ddf.minim.Minim;
import ddf.minim.AudioInput;
import processing.core.PVector;

/**
 * Core controller for a HeronLX instance. Each instance drives a
 * grid of nodes with a fixed width and height.
 */
public class HeronLX {

	public final static String VERSION = "##library.prettyVersion##";

	/**
	 * Returns the version of the library.
	 *
	 * @return String
	 */
	public static String version() {
		return VERSION;
	}

	/**
	 * A reference to the applet context.
	 */
	public final PApplet applet;

	/**
	 * The width of the grid, immutable.
	 */
	public final int width;

	/**
	 * The height of the grid, immutable.
	 */
	public final int height;

	/**
	 * The midpoint of the x-space, immutable.
	 */
	public final double midwidth;

	/**
	 * This midpoint of the y-space, immutable.
	 */
	public final double midheight;

	/**
	 * The total number of pixels in the grid, immutable.
	 */
	public final int total;

	private final Engine engine;
	private final Simulation simulation;
	private RGBNodeOutput RGBNodeOutput;
	private final int[] dimmedColors;
	private ClientListener client;
	private double brightness;

	/**
	 * Whether drawing is enabled
	 */
	private boolean drawSimulation;

	/**
	 * The global tempo object.
	 */
	public final Tempo tempo;

	/**
	 * The global touch object.
	 */
	private Touch touch;

	/**
	 * Minim instance to provide audio input.
	 */
	private Minim minim;

	/**
	 * The global audio input.
	 */
	private AudioInput audioInput;

	/**
	 * Global modulator for shared base hue.
	 */
	private LXModulator baseHue;

	/**
	 * Global flash effect.
	 */
	private final FlashEffect flash;

	/**
	 * Global desaturation effect.
	 */
	private final DesaturationEffect desaturation;

	private final class Flags {
		public boolean showFramerate = false;
		public boolean keyboardTempo = false;
	}

	private final Flags flags = new Flags();

	/**
	 * Creates a HeronLX instance. This instance will run patterns
	 * for a grid of the specified size.
	 *
	 * @param applet
	 * @param width
	 * @param height
	 */
	public HeronLX(PApplet applet, int width, int height) {
		this.applet = applet;
		this.width = width;
		this.height = height;
		this.midwidth = (width-1)/2.;
		this.midheight = (height-1)/2.;
		this.total = width * height;
		this.RGBNodeOutput = null;
		this.client = null;

		this.drawSimulation = true;

		this.engine = new Engine(this);
		this.simulation = new Simulation(this);

		this.baseHue = null;
		this.cycleBaseHue(30000);

		this.brightness = 1.;
		this.dimmedColors = new int[this.total];

		this.touch = new Touch.NullTouch();
		this.tempo = new Tempo();

		this.desaturation = new DesaturationEffect(this);
		this.flash = new FlashEffect(this);

		applet.colorMode(PConstants.HSB, 360, 100, 100, 100);
		applet.registerDraw(this);
		applet.registerSize(this);
		applet.registerKeyEvent(this);
	}

	public void enableBasicEffects() {
		this.addEffect(this.desaturation);
		this.addEffect(this.flash);
	}

	public void enableKeyboardTempo() {
		this.flags.keyboardTempo = true;
	}

	public void dispose() {
		if (this.audioInput != null) {
			this.audioInput.close();
		}
		if (this.minim != null) {
			this.minim.stop();
		}
	}

	/**
	 * Utility to create a color from double values
	 *
	 * @param h Hue
	 * @param s Saturation
	 * @param b Brightness
	 * @return Color value
	 */
	public final int colord(double h, double s, double b) {
		return this.applet.color((float)h, (float)s, (float)b);
	}

	/**
	 * Utility logging function
	 *
	 * @param s Logs the string with relevant prefix
	 */
	private void log(String s) {
		System.out.println("HeronLX: " + s);
	}

	/**
	 * Returns the current color values
	 *
	 * @return Array of the current color values
	 */
	public final int[] getColors() {
		return this.engine.getColors();
	}

	/**
	 * Return the currently active transition
	 *
	 * @return A transition if one is active
	 */
	public final LXTransition getTransition() {
		return this.engine.getActiveTransition();
	}

	/**
	 * Returns the current pattern
	 *
	 * @return Currently active pattern
	 */
	public final LXPattern getPattern() {
		return this.engine.getActivePattern();
	}

	/**
	 * Returns the pattern being transitioned to
	 *
	 * @return Next pattern
	 */
	public final LXPattern getNextPattern() {
		return this.engine.getNextPattern();
	}

	/**
	 * Utility method to access the touch object.
	 *
	 * @return The touch object
	 */
	public Touch touch() {
		return this.touch;
	}

	public final AudioInput audioInput() {
		if (audioInput == null) {
			// Lazily instantiated on-demand
			this.minim = new Minim(this.applet);
			this.audioInput = minim.getLineIn(Minim.STEREO, 1024);
		}
		return audioInput;
	}

	/**
	 * Utility function to return the row of a given index
	 *
	 * @param i Index into colors array
	 * @return Which row this index is in
	 */
	public int row(int i) {
		return i / this.width;
	}

	/**
	 * Utility function to return the position of an index in x coordinate
	 * space from 0 to 1.
	 *
	 * @param i
	 * @return Position of this node in x space, from 0 to 1
	 */
	public double xpos(int i) {
		return (i % this.width) / (float) (this.width - 1);
	}

	public float xposf(int i) {
		return (float)this.xpos(i);
	}

	/**
	 * Utility function to return the position of an index in y coordinate
	 * space from 0 to 1.
	 *
	 * @param i
	 * @return Position of this node in y space, from 0 to 1
	 */
	public double ypos(int i) {
		return (i / this.width) / (float) (this.height - 1);
	}

	public float yposf(int i) {
		return (float)this.ypos(i);
	}

	/**
	 * Utility function to return the column of a given index
	 *
	 * @param i Index into colors array
	 * @return Which column this index is in
	 */
	public int column(int i) {
		return i % this.width;
	}

	/**
	 * Utility function to return the index of a given x and y
	 *
	 * @param x Column into colors array
	 * @param y Row into colors array
	 * @return Which index the x and y is at
	 */
	public int index(int x, int y) {
		assert 0 <= x && x < this.width;
		assert 0 <= y && y < this.height;
		return y * this.width + x;
	}

	/**
	 * Utility function to return the index of a given position
	 *
	 * @param position Point in the node matrix
	 * @return Which index the position is at
	 */
	public int index(PVector position) {
		return this.index((int)position.x, (int)position.y);
	}

	/**
	 *
	 * @return A vector corresponding to the center of the matrix
	 */
	public PVector getCenterPosition() {
		return new PVector((int) this.midwidth, (int) this.midheight);
	}


	/**
	 * Sets brightness factor of the KiNET output, does NOT impact screen simulation
	 *
	 * @param brightness Value from 0-100 scale
	 */
	public void setBrightness(double brightness) {
		this.brightness = LXUtils.constrain(brightness/100., 0, 1);
	}

	/**
	 * The effects chain
	 *
	 * @return The full effects chain
	 */
	public List<LXEffect> getEffects() {
		return this.engine.getEffects();
	}

	/**
	 * Add multiple effects to the chain
	 *
	 * @param effects
	 */
	public void addEffects(LXEffect[] effects) {
		for (LXEffect effect : effects) {
			addEffect(effect);
		}
	}

	/**
	 * Add an effect to the FX chain.
	 *
	 * @param effect
	 * @return Effect added
	 */
	public LXEffect addEffect(LXEffect effect) {
		this.engine.addEffect(effect);
		return effect;
	}

	/**
	 * Remove an effect from the chain
	 *
	 * @param effect
	 */
	public void removeEffect(LXEffect effect) {
		this.engine.removeEffect(effect);
	}

	/**
	 * Add a generic modulator to the engine
	 *
	 * @param modulator
	 * @return Modulator added
	 */
	public LXModulator addModulator(LXModulator modulator) {
		this.engine.addModulator(modulator);
		return modulator;
	}

	/**
	 * Remove a modulator from the engine
	 *
	 * @param modulator
	 */
	public void removeModulator(LXModulator modulator) {
		this.engine.removeModulator(modulator);
	}

	public void flash() {
		this.flash.trigger();
	}

	public void goPrev() {
		this.engine.goPrev();
	}

	public void goNext() {
		this.engine.goNext();
	}

	public void goIndex(int i) {
		this.engine.goIndex(i);
	}

	/**
	 * Returns the base hue shared across patterns
	 *
	 * @return Base hue value shared by all patterns
	 */
	public double getBaseHue() {
		if (this.baseHue == null) {
			return 0;
		}
		return (this.baseHue.getValue() + 360) % 360;
	}

	public float getBaseHuef() {
		return (float)this.getBaseHue();
	}

	/**
	 * Sets the base hue to a fixed value
	 *
	 * @param hue Fixed value to set hue to, 0-360
	 */
	public void setBaseHue(double hue) {
		this.engine.removeModulator(this.baseHue);
		this.engine.addModulator(this.baseHue = new LinearEnvelope(this.getBaseHue(), hue, 50).trigger());
	}

	/**
	 * Sets the base hue to cycle through the spectrum
	 *
	 * @param duration Number of milliseconds for hue cycle
	 */
	public void cycleBaseHue(double duration) {
		double currentHue = this.getBaseHue();
		this.engine.removeModulator(this.baseHue);
		this.engine.addModulator(this.baseHue = new SawLFO(0, 360, duration).setValue(currentHue).start());
	}

	/**
	 * Sets the base hue to oscillate between two spectrum values
	 *
	 * @param lowHue Low hue value
	 * @param highHue High hue value
	 * @param duration Milliseconds for hue oscillation
	 */
	public void oscillateBaseHue(double lowHue, double highHue, double duration) {
		double value = this.getBaseHue();
		this.engine.removeModulator(this.baseHue);
		this.engine.addModulator(this.baseHue = new TriangleLFO(lowHue, highHue, duration).setValue(value).trigger());
	}

	/**
	 * Stops patterns from automatically rotating
	 */
	public void disableAutoTransition() {
		this.engine.disableAutoTransition();
	}

	/**
	 * Sets the patterns to rotate automatically
	 *
	 * @param autoTransitionThreshold Number of milliseconds after which to rotate pattern
	 */
	public void enableAutoTransition(int autoTransitionThreshold) {
		this.engine.enableAutoTransition(autoTransitionThreshold);
	}

	/**
	 * Whether auto transition is enabled.
	 *
	 * @return Whether auto transition is enabled.
	 */
	public boolean isAutoTransitionEnabled() {
		return this.engine.isAutoTransitionEnabled();
	}

	/**
	 * Enable the simulation renderer
	 *
	 * @param s Whether to automatically draw a simulation
	 */
	public void enableSimulation(boolean s) {
		this.drawSimulation = s;
	}

	/**
	 * Sets the size of the drawn simulation in the Processing window
	 *
	 * @param x Top left x coordinate
	 * @param y Top left y coordinate
	 * @param w Width of simulation in pixels
	 * @param h Height of simulation in pixels
	 */
	public void setSimulationBounds(int x, int y, int w, int h) {
		this.simulation.setBounds(x, y, w, h);
	}

	/**
	 * Sets the drawing style of the pixels in the simulation.
	 *
	 * @param pixelStyle Style of the pixel (Simulation.PIXEL_STYLE_SMALL_POINT=0 or Simulation.PIXEL_STYLE_FULL_RECT=1)
	 */
	public void setSimulationPixelStyle(int pixelStyle) {
		this.simulation.setPixelStyle(pixelStyle);
	}

	/**
	 * Listens for UDP packets from HeronLX clients.
	 */
	public void enableClientListener() {
		this.client = ClientListener.getInstance();
		this.client.addListener(this);
		this.touch = this.client.touch();
	}

	/**
	 * Specifies an array of kinet nodes to send output to.
	 *
	 * @param kinetNodes Array of KinetNode objects, must have length equal to width * height
	 */
	public void setKinetNodes(KinetNode[] kinetNodes) {
		if (kinetNodes == null) {
			this.RGBNodeOutput = null;
		} else if (kinetNodes.length != this.total) {
			throw new RuntimeException("Array provided to setKinetNodes is the wrong length, must equal length of HeronLX, use null for non-mapped output nodes.");
		} else {
			try {
				this.RGBNodeOutput = new Kinet(kinetNodes);
			} catch (SocketException sx) {
				this.RGBNodeOutput = null;
				throw new RuntimeException("Could not create UDP socket for Kinet", sx);
			}
		}
	}

	/**
	 * Specifies a Kinet object to run lighting output
	 *
	 * @param kinet Kinet instance with total size equal to width * height
	 */
	public void setKinet(Kinet kinet) {
		if (kinet != null && (kinet.size() != this.total)) {
			throw new RuntimeException("Kinet provided to setKinet is the wrong size, must equal length of HeronLX, use null for non-mapped output nodes.");
		}
		this.RGBNodeOutput = kinet;
	}

	/**
	 * Specifies a DMX output object to run lighting output to display the RGB nodes given by this.colors.
	 *
	 * @param DMXOutput DMXOutput instance with total size equal to width * height
	 */
	public void setDMXOuput(DMXOutput DMXOutput) {
		if (DMXOutput == null) {
			this.RGBNodeOutput = null;
		} else if (DMXOutput.size() != this.total) {
			throw new RuntimeException("Array provided to setDMXOuput is the wrong length, must equal length of HeronLX, use null for non-mapped output nodes.");
		} else {
			this.RGBNodeOutput = DMXOutput;
		}
	}

	/**
	 * Specifies the set of patterns to be run.
	 *
	 * @param patterns
	 */
	public void setPatterns(LXPattern[] patterns) {
		this.engine.setPatterns(patterns);
	}

	/**
	 * Gets the current set of patterns
	 *
	 * @return The pattern set
	 */
	public LXPattern[] getPatterns() {
		return this.engine.getPatterns();
	}

	public void draw() {
		if (this.client != null) {
			this.client.listen();
		}
		this.engine.run();
		int[] colors = this.engine.getColors();
		if (this.RGBNodeOutput != null) {
			if (this.brightness < 1.) {
				float factor = (float) (this.brightness * this.brightness);
				for (int i = 0; i < colors.length; ++i) {
					this.dimmedColors[i] = this.applet.color(
						this.applet.hue(colors[i]),
						this.applet.saturation(colors[i]),
						this.applet.brightness(colors[i]) * factor
					);
				}
				this.RGBNodeOutput.sendThrottledColors(this.dimmedColors);
			} else {
				this.RGBNodeOutput.sendThrottledColors(colors);
			}
		}
		if (this.drawSimulation) {
			this.simulation.draw(colors);
		}
		if (this.flags.showFramerate) {
			System.out.println("Framerate: " + this.applet.frameRate);
		}
	}

	public void exit() {
		System.out.println("EXIT");
	}

	public void size(int width, int height) {
		this.setSimulationBounds(0, 0, width, height);
	}

	public void keyEvent(KeyEvent e) {
		if (e.getID() == KeyEvent.KEY_RELEASED) {
			switch (e.getKeyCode()) {
			case KeyEvent.VK_UP:
				this.engine.goPrev();
				break;
			case KeyEvent.VK_DOWN:
				this.engine.goNext();
				break;
			case KeyEvent.VK_LEFT:
				if (this.flags.keyboardTempo) {
					this.tempo.setBpm(this.tempo.bpm() - .1);
				}
				break;
			case KeyEvent.VK_RIGHT:
				if (this.flags.keyboardTempo) {
					this.tempo.setBpm(this.tempo.bpm() + .1);
				}
				break;
			}

			switch (e.getKeyChar()) {
			case '[':
				this.engine.goPrev();
				break;
			case ']':
				this.engine.goNext();
				break;
			case 'f':
			case 'F':
				this.flags.showFramerate = !this.flags.showFramerate;
				break;
			case ' ':
				if (this.flags.keyboardTempo) {
					this.tempo.tap();
				}
				break;
			case 's':
			case 'S':
				this.desaturation.disable();
				break;
			case '/':
				this.flash.disable();
				break;
			}
		} else if (e.getID() == KeyEvent.KEY_PRESSED) {
			switch (e.getKeyChar()) {
			case 's':
			case 'S':
				this.desaturation.enable();
				break;
			case '/':
				this.flash.enable();
				break;
			}
		}
	}

	public final PGraphics getGraphics() {
		return this.applet.g;
	}
}


package org.osmdroid.views.overlay;

import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;

import org.osmdroid.util.Distance;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.PointL;
import org.osmdroid.util.RectL;
import org.osmdroid.util.SegmentClipper;
import org.osmdroid.util.TileSystem;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;

import java.util.ArrayList;
import java.util.List;

/**
 * Class holding one ring: the polygon outline, or a hole inside the polygon
 * Used to be an inner class of {@link Polygon}
 * @since 6.0.0
 * @author Fabrice Fontaine
 */
class LinearRing implements SegmentClipper.SegmentClippable{

	/**
	 * We build a virtual area [mClipMin, mClipMin, mClipMax, mClipMax]
	 * used to clip our Path in order to cope with
	 * - very high pixel values (that go beyond the int values, for instance on zoom 29)
	 * - some kind of Android bug related to hardware acceleration
	 * "One size fits all" clip area values cannot be determined
	 * as there's not explicit value given by Android to avoid Path drawing issues.
	 * If the size is too big (magnitude around Integer.MAX_VALUE), the Path won't show properly.
	 * If it's small (just above the size of the screen), the approximations of the clipped Path
	 * may look gross, particularly if you zoom out in animation.
	 * The smaller it is, the better it is for performances because the clip
	 * will then often approximate consecutive Path segments as identical, and we only add
	 * distinct points to the Path as an optimization.
	 * The best idea so far is to compute the clip area border values
	 * from the current MapView's characteristics (width, height, scale, orientation)
	 */

	private final ArrayList<GeoPoint> mOriginalPoints = new ArrayList<>();
	private final ArrayList<PointL> mProjectedPoints = new ArrayList<>();
	private final PointL mLatestPathPoint = new PointL();
	private SegmentClipper mSegmentClipper;
	private final Path mPath;
	private boolean mIsNextAMove;
	private boolean mPrecomputed;
	private boolean isHoritonalRepeating = true;
	private boolean isVerticalRepeating  = true;

	private final ArrayList<Path> mDirectionalArrows = new ArrayList<>();
	private static final float DEFAULT_ARROW_LENGTH = 20f;
	private static final boolean DEFAULT_INVERT_ARROWS = false;
	private boolean mDrawDirectionalArrows = false;
	private boolean mInvertDirectionalArrows = DEFAULT_INVERT_ARROWS;
	private float mDirectionalArrowLength = DEFAULT_ARROW_LENGTH;

	public LinearRing(final Path pPath) {
		mPath = pPath;
	}

	@Override
	public void init() {
		mIsNextAMove = true;
	}

	@Override
	public void lineTo(final long pX, final long pY) {
		if (mIsNextAMove) {
			mIsNextAMove = false;
			mPath.moveTo(pX, pY);
			mLatestPathPoint.set(pX, pY);
		} else {
			if (mLatestPathPoint.x != pX || mLatestPathPoint.y != pY) {
				mPath.lineTo(pX, pY);
				mLatestPathPoint.set(pX, pY);
			}
		}
	}

	void clearPath() {
		mOriginalPoints.clear();
		mDirectionalArrows.clear();
		mPrecomputed = false;
	}

	void addPoint(final GeoPoint pGeoPoint) {
		mOriginalPoints.add(pGeoPoint);
		mPrecomputed = false;
	}

	ArrayList<GeoPoint> getPoints(){
		return mOriginalPoints;
	}

	void setPoints(final List<GeoPoint> points) {
		clearPath();
		mOriginalPoints.addAll(points);
	}

	/**
	 * A directional arrow is a single arrow drawn in the middle of two points of a polyline to
	 * provide a visual cue for direction of movement between the two points.
	 *
	 * By default the arrows always point towards the lower index as the list of GeoPoints are
	 * processed. The direction the arrows point can be inverted. You can adjust the length
	 * (in pixels) of how far the arrows extend away from the line.
 	 *
	 * @param drawDirectionalArrows to enable or disable
	 * @param arrowLength the length in pixels the arrow should be. Use null for default value
	 * @param invertDirection invert the direction the arrows are drawn. Use null for default value
	 */
	void setDrawDirectionalArrows(
			boolean drawDirectionalArrows, Float arrowLength, Boolean invertDirection) {
		this.mDrawDirectionalArrows = drawDirectionalArrows;
		// reset defaults if disabling
		if (!drawDirectionalArrows) {
			mDirectionalArrowLength = DEFAULT_ARROW_LENGTH;
			mInvertDirectionalArrows = DEFAULT_INVERT_ARROWS;
			return;
		}
		if (arrowLength != null) {
			this.mDirectionalArrowLength = arrowLength;
		}
		if (invertDirection != null) {
			this.mInvertDirectionalArrows = invertDirection;
		}
	}

	ArrayList<Path> getDirectionalArrowPaths() {
		return mDirectionalArrows;
	}

	/**
	 * Feed the path with the segments corresponding to the GeoPoint pairs
	 * projected using pProjection and clipped into a "reasonable" clip area
	 * In most cases (Polygon without holes, Polyline) the offset parameter will be null.
	 * In the case of a Polygon with holes, the first path will use a null offset.
	 * Then this method will return the pixel offset computed for this path so that
	 * the path is in the best possible place on the map (maximum area on the map + top left).
	 * Then, this computed offset must be injected into the buildPathPortion for each hole,
	 * in order to have the main polygon and its holes at the same place on the map.
	 * @return the initial offset if not null, or the computed offset
	 */
	PointL buildPathPortion(final Projection pProjection,
							final boolean pClosePath, final PointL pOffset){
		final int size = mOriginalPoints.size();
		if (size < 2) { // nothing to paint
			return pOffset;
		}

		if (!mPrecomputed){
			getProjectedFromGeo(mOriginalPoints, mProjectedPoints, pProjection);
			mPrecomputed = true;
		}

		final List<RectL> segments = new ArrayList<>();

		if (!mDirectionalArrows.isEmpty()) {
			mDirectionalArrows.clear();
		}
		getSegmentsFromProjected(pProjection, mProjectedPoints, segments, pClosePath);
		final PointL offset;
		if (pOffset != null) {
			offset = pOffset;
		} else {
			offset = new PointL();
			getBestOffset(segments, pProjection, offset);
		}
		applyOffset(segments, offset);
		init();
		clip(segments);
		if (pClosePath) {
			mPath.close();
		}
		return offset;
	}

	/**
	 * Compute the pixel offset so that a list of pixel segments display in the best possible way:
	 * the biggest possible covered area, and top left.
	 * This notion of pixel offset only has a meaning on very low zoom level,
	 * when a GeoPoint can be projected on different places on the screen.
	 */
	private void getBestOffset(final List<RectL> pSegments,
							   final Projection pProjection, final PointL pOffset) {
		final Rect screenRect = pProjection.getIntrinsicScreenRect();
		final double worldSize = TileSystem.MapSize(pProjection.getZoomLevel());
		final RectL boundingBox = getBoundingBox(pSegments, null);
		getBestOffset(boundingBox, screenRect, worldSize, pOffset);
	}

	private void getBestOffset(final RectL pBoundingBox, final Rect pScreenRect,
							   final double pWorldSize, final PointL pOffset) {
		final long worldSize = Math.round(pWorldSize);
		int deltaXPositive = getBestOffset(pBoundingBox, pScreenRect, worldSize, 0);
		int deltaXNegative = getBestOffset(pBoundingBox, pScreenRect, -worldSize, 0);
		int deltaYPositive = getBestOffset(pBoundingBox, pScreenRect, 0, worldSize);
		int deltaYNegative = getBestOffset(pBoundingBox, pScreenRect, 0, -worldSize);
		if (!isVerticalRepeating) {
			deltaYPositive=0;
			deltaYNegative=0;
		}
		if (!isHoritonalRepeating) {
			deltaXPositive=0;
			deltaXNegative=0;
		}
		pOffset.x = worldSize * (deltaXPositive > deltaXNegative ? deltaXPositive:-deltaXNegative);
		pOffset.y = worldSize * (deltaYPositive > deltaYNegative ? deltaYPositive:-deltaYNegative);
	}

	private int getBestOffset(final RectL pBoundingBox, final Rect pScreenRect,
							  final long pDeltaX, final long pDeltaY) {
		if (!isHoritonalRepeating && !isVerticalRepeating ) {
			return 0;
		}
		final double boundingBoxCenterX = (pBoundingBox.left + pBoundingBox.right) / 2.;
		final double boundingBoxCenterY = (pBoundingBox.top + pBoundingBox.bottom) / 2.;
		final double screenRectCenterX = (pScreenRect.left + pScreenRect.right) / 2.;
		final double screenRectCenterY = (pScreenRect.top + pScreenRect.bottom) / 2.;
		double squaredDistance = 0;
		int i = 0;
		while(true) {
			final double tmpSquaredDistance = Distance.getSquaredDistanceToPoint(
					boundingBoxCenterX + i * pDeltaX, boundingBoxCenterY + i * pDeltaY,
					screenRectCenterX, screenRectCenterY);
			if (i == 0 || squaredDistance > tmpSquaredDistance) {
				squaredDistance = tmpSquaredDistance;
				i ++;
			} else {
				break;
			}
		}
		return i - 1;
	}

	private RectL getBoundingBox(final List<RectL> pSegments, final RectL pReuse) {
		final RectL out = pReuse != null ? pReuse : new RectL();
		boolean first = true;
		for (final RectL segment : pSegments) {
			final long xMin = Math.min(segment.left, segment.right);
			final long xMax = Math.max(segment.left, segment.right);
			final long yMin = Math.min(segment.top, segment.bottom);
			final long yMax = Math.max(segment.top, segment.bottom);
			if (first) {
				first = false;
				out.left = xMin;
				out.right = xMax;
				out.top = yMin;
				out.bottom = yMax;
			} else {
				if (out.left > xMin) {
					out.left = xMin;
				}
				if (out.top > yMin) {
					out.top = yMin;
				}
				if (out.right < xMax) {
					out.right = xMax;
				}
				if (out.bottom < yMax) {
					out.bottom = yMax;
				}
			}
		}
		return out;
	}

	private void getProjectedFromGeo(final List<GeoPoint> pGeo, final ArrayList<PointL> pProjected,
									final Projection pProjection) {
		pProjected.clear();
		pProjected.ensureCapacity(pGeo.size());
		for (final GeoPoint geoPoint : pGeo) {
			pProjected.add(pProjection.toProjectedPixels(
					geoPoint.getLatitude(), geoPoint.getLongitude(), null));
		}
	}

	private void getSegmentsFromProjected(final Projection pProjection,
										  final List<PointL> pProjectedPoints,
										  final List<RectL> pSegments,
										  final boolean pClosePath) {
		final double worldSize = TileSystem.MapSize(pProjection.getZoomLevel());
		final double powerDifference = pProjection.getProjectedPowerDifference();
		final PointL screenPoint0 = new PointL(); // points on screen
		final PointL screenPoint1 = new PointL();
		PointL firstPoint = null;
		for (final PointL projectedPoint : pProjectedPoints) {
			// compute next points
			pProjection.getLongPixelsFromProjected(
					projectedPoint, powerDifference, false, screenPoint1);
			if (firstPoint == null) {
				firstPoint = new PointL(screenPoint1);
			} else {
				setCloserPoint(screenPoint0, screenPoint1, worldSize);
				pSegments.add(new RectL(screenPoint0.x, screenPoint0.y, screenPoint1.x, screenPoint1.y));

				if (mDrawDirectionalArrows) {
					addDirectionalArrow(screenPoint0, screenPoint1);
				}
			}

			// update starting point to next position
			screenPoint0.set(screenPoint1);
		}
		if (pClosePath) {
			if (firstPoint != null) {
				pSegments.add(new RectL(screenPoint0.x, screenPoint0.y, firstPoint.x, firstPoint.y));
			}
		}
	}

	/**
	 * We want consecutive projected points to be as close as possible,
	 * and not a world away (typically when dealing with very low zoom levels)
	 */
	private void setCloserPoint(final PointL pPrevious, final PointL pNext,
								final double pWorldSize) {
		while (Math.abs(pNext.x - pWorldSize - pPrevious.x) < Math.abs(pNext.x - pPrevious.x)) {
			pNext.x -= pWorldSize;
		}
		while (Math.abs(pNext.x + pWorldSize - pPrevious.x) < Math.abs(pNext.x - pPrevious.x)) {
			pNext.x += pWorldSize;
		}
		while (Math.abs(pNext.y - pWorldSize - pPrevious.y) < Math.abs(pNext.y - pPrevious.y)) {
			pNext.y -= pWorldSize;
		}
		while (Math.abs(pNext.y + pWorldSize - pPrevious.y) < Math.abs(pNext.y - pPrevious.y)) {
			pNext.y += pWorldSize;
		}
	}

	/**
	 * Detection is done in screen coordinates.
	 * @param tolerance in pixels
	 * @return true if the Polyline is close enough to the point.
	 */
	boolean isCloseTo(final GeoPoint pPoint, final double tolerance,
							 final Projection pProjection) {
		if (!mPrecomputed){
			getProjectedFromGeo(mOriginalPoints, mProjectedPoints, pProjection);
			mPrecomputed = true;
		}
		final Point pixel = pProjection.toPixels(pPoint, null);
		final List<RectL> segments = new ArrayList<>();
		getSegmentsFromProjected(pProjection, mProjectedPoints, segments, false);
		final PointL offset = new PointL();
		getBestOffset(segments, pProjection, offset);
		applyOffset(segments, offset);
		clip(segments);
		final double squaredTolerance = tolerance * tolerance;
		for (final RectL segment : segments) {
			if (squaredTolerance > Distance.getSquaredDistanceToSegment(
					pixel.x, pixel.y, segment.left, segment.top, segment.right, segment.bottom)) {
				return true;
			}
		}
		return false;
	}

	private void applyOffset(final List<RectL> pSegments, final PointL pOffset) {
		if (pOffset.x == 0 && pOffset.y == 0) {
			return;
		}
		for (final RectL segment : pSegments) {
			if (pOffset.x != 0) {
				segment.left += pOffset.x;
				segment.right += pOffset.x;
			}
			if (pOffset.y != 0) {
				segment.top += pOffset.y;
				segment.bottom += pOffset.y;
			}
		}
	}

	private void clip(final List<RectL> pSegments) {
		for (final RectL segment : pSegments) {
			mSegmentClipper.clip(segment);
		}
	}

	/**
	 * @since 6.0.0
	 * Mandatory use before clipping.
	 * Possible optimization: if we're dealing with the same border values,
	 * we can use the same SegmentClipper instead of constructing a new one at each canvas draw.
	 */
	public void setClipArea(final long pXMin, final long pYMin, final long pXMax, final long pYMax) {
		mSegmentClipper = new SegmentClipper(pXMin, pYMin, pXMax, pYMax, this);
	}

	/**
	 * @since 6.0.0
	 * Mandatory use before clipping.
	 */
	public void setClipArea(final MapView pMapView) {
		final double border = .1;
		final int halfWidth = pMapView.getWidth() / 2;
		final int halfHeight = pMapView.getHeight() / 2;
		// People less lazy than me would do more refined computations for width and height
		// that include the map orientation: the covered area would be smaller but still big enough
		// Now we use the circle which contains the `MapView`'s 4 corners
		final double radius = Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);
		final int scaledRadius = (int) ((radius / pMapView.getMapScale()) * (1 + border));
		setClipArea(
				halfWidth - scaledRadius, halfHeight - scaledRadius,
				halfWidth + scaledRadius, halfHeight + scaledRadius
		);
		// TODO: Not sure if this is the correct approach
		this.isHoritonalRepeating = pMapView.isHorizontalMapRepetitionEnabled();
		this.isVerticalRepeating = pMapView.isVerticalMapRepetitionEnabled();
	}

	private void addDirectionalArrow(PointL screenPoint0, PointL screenPoint1) {
		// if the points are really close don't draw an arrow
		if (Math.abs(screenPoint0.x - screenPoint1.x) +
				Math.abs(screenPoint0.y - screenPoint1.y) <= mDirectionalArrowLength) {
			return;
		}

		if (mInvertDirectionalArrows) {
			PointL temp = screenPoint0;
			screenPoint0 = screenPoint1;
			screenPoint1 = temp;
		}

		// mid point in projected pixels
		PointL screenPoint3 = new PointL();
		PointL screenPoint4 = new PointL();
		screenPoint3.x = (screenPoint0.x + screenPoint1.x) / 2;
		screenPoint3.y = (screenPoint0.y + screenPoint1.y) / 2;
		double delta_x = screenPoint1.x - screenPoint0.x;
		double delta_y = screenPoint1.y - screenPoint0.y;

		float distance = mDirectionalArrowLength;

		if (delta_x == 0) {
			screenPoint3.x = screenPoint1.x;
			screenPoint4.x = screenPoint1.x;

			if (screenPoint0.y > screenPoint1.y) {
				screenPoint3.y = screenPoint0.y + (int) Math.round(delta_y/2);
				screenPoint4.y = screenPoint3.y - (int) distance;
			} else {
				screenPoint3.y = screenPoint0.y + (int) Math.round(delta_y/2);
				screenPoint4.y = screenPoint3.y + (int) distance;
			}
		} else if (delta_y == 0) {
			screenPoint3.y = screenPoint1.y;
			screenPoint4.y = screenPoint1.y;

			if (screenPoint0.x > screenPoint1.x) {
				screenPoint3.x = screenPoint0.x + (int) Math.round(delta_x/2);
				screenPoint4.x = screenPoint3.x - (int) distance;
			} else {
				screenPoint3.x = screenPoint0.x + (int) Math.round(delta_x/2);
				screenPoint4.x = screenPoint3.x + (int) distance;
			}
		} else {
			 double slope = delta_y / delta_x;
			 /*  The formula will calculate a new X,Y coordinate that is some distance away from
				a given coordinate when a slope is known. Distance is the correct way of
				thinking about what the number is in regards to the formula used. It can also
				be thought of as the length of the squares diagonal.
			*/
			double r = Math.sqrt(1 + Math.pow(slope, 2));
			// move pt3 half the distance of the square so pt 5 and 6 will intersect the midpt
			screenPoint3.x = screenPoint3.x + (int) Math.round((distance / 2) / r);
			screenPoint3.y = screenPoint3.y + (int) Math.round((distance / 2) * slope / r);
			//calculate another point on the line that is distance in px away from the mid point
			screenPoint4.x = screenPoint3.x - (int) Math.round((distance / r));
			screenPoint4.y = screenPoint3.y - (int) Math.round((distance * slope / r));
		}
		/* 3rd point in the square
			= ( ( x1 + x3 + y1 - y3 ) / 2 , ( x3 - x1 + y1 + y3 ) / 2 )
		*/
		PointL screenPoint5 = new PointL();
		screenPoint5.x =
				(screenPoint4.x + screenPoint3.x + screenPoint4.y - screenPoint3.y) / 2;
		screenPoint5.y =
				(screenPoint3.x - screenPoint4.x + screenPoint4.y + screenPoint3.y) / 2;
		/* 4th point in the square
			= ( ( x1 + x3 + y3 - y1 ) / 2 , ( x1 - x3 + y1 + y3 ) / 2 )
		*/
		PointL screenPoint6 = new PointL();
		screenPoint6.x =
				(screenPoint4.x + screenPoint3.x + screenPoint3.y - screenPoint4.y) / 2;
		screenPoint6.y =
				(screenPoint4.x - screenPoint3.x + screenPoint3.y + screenPoint4.y) / 2;

		Path directionalArrowPath = new Path();
		if (screenPoint0.x > screenPoint1.x && screenPoint0.y > screenPoint1.y) {
			directionalArrowPath.moveTo(screenPoint5.x, screenPoint5.y);
			directionalArrowPath.lineTo(screenPoint3.x, screenPoint3.y);
			directionalArrowPath.lineTo(screenPoint6.x, screenPoint6.y);
		} else if (screenPoint0.x < screenPoint1.x && screenPoint0.y < screenPoint1.y) {
			directionalArrowPath.moveTo(screenPoint5.x, screenPoint5.y);
			directionalArrowPath.lineTo(screenPoint4.x, screenPoint4.y);
			directionalArrowPath.lineTo(screenPoint6.x, screenPoint6.y);
		} else if (screenPoint0.x < screenPoint1.x && screenPoint0.y > screenPoint1.y) {
			directionalArrowPath.moveTo(screenPoint5.x, screenPoint5.y);
			directionalArrowPath.lineTo(screenPoint4.x, screenPoint4.y);
			directionalArrowPath.lineTo(screenPoint6.x, screenPoint6.y);
		} else {
			directionalArrowPath.moveTo(screenPoint5.x, screenPoint5.y);
			directionalArrowPath.lineTo(screenPoint3.x, screenPoint3.y);
			directionalArrowPath.lineTo(screenPoint6.x, screenPoint6.y);
		}
		directionalArrowPath.close();

		mDirectionalArrows.add(directionalArrowPath);
	}
}

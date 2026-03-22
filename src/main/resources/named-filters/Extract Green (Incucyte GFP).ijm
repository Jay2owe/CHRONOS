// Extract GFP from Incucyte RGB via HSB Saturation
// Removes calibration, extracts saturation channel, double paraboloid, median fill
run("Properties...", "pixel_width=1 pixel_height=1 voxel_depth=1 unit=pixel");
run("HSB Stack");
nFrames = nSlices / 3;
run("Stack to Hyperstack...", "order=xyczt(default) channels=3 slices=" + nFrames + " frames=1");
run("Duplicate...", "title=GFP_extracted duplicate channels=2");
// Close HSB hyperstack (it kept the original title)
list = getList("image.titles");
for (i = 0; i < list.length; i++) {
  if (list[i] != "GFP_extracted") { selectWindow(list[i]); close(); }
}
selectWindow("GFP_extracted");
run("Subtract Background...", "rolling=50 sliding stack");
run("Subtract Background...", "rolling=15 sliding stack");
run("Median...", "radius=1 stack");

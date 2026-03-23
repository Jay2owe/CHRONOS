// Extract HSB Brightness channel
// Useful for bioluminescence where hue/saturation are irrelevant
run("Properties...", "pixel_width=1 pixel_height=1 voxel_depth=1 unit=pixel");
run("HSB Stack");
nFrames = nSlices / 3;
run("Stack to Hyperstack...", "order=xyczt(default) channels=3 slices=" + nFrames + " frames=1");
run("Duplicate...", "title=Brightness duplicate channels=3");
list = getList("image.titles");
for (i = 0; i < list.length; i++) {
  if (list[i] != "Brightness") { selectWindow(list[i]); close(); }
}
selectWindow("Brightness");

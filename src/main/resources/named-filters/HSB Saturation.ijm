// Extract HSB Saturation channel
// Useful for isolating coloured signal from variable-brightness background
run("Properties...", "pixel_width=1 pixel_height=1 voxel_depth=1 unit=pixel");
run("HSB Stack");
nFrames = nSlices / 3;
run("Stack to Hyperstack...", "order=xyczt(default) channels=3 slices=" + nFrames + " frames=1");
run("Duplicate...", "title=Saturation duplicate channels=2");
list = getList("image.titles");
for (i = 0; i < list.length; i++) {
  if (list[i] != "Saturation") { selectWindow(list[i]); close(); }
}
selectWindow("Saturation");

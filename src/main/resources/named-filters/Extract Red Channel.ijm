// Extract Red channel from RGB time-lapse
// Splits channels, keeps red, cleans with background subtraction
run("Properties...", "pixel_width=1 pixel_height=1 voxel_depth=1 unit=pixel");
run("Split Channels");
// Close green and blue
list = getList("image.titles");
for (i = 0; i < list.length; i++) {
  if (indexOf(list[i], "(green)") >= 0 || indexOf(list[i], "(blue)") >= 0) {
    selectWindow(list[i]); close();
  }
}
run("Subtract Background...", "rolling=50 sliding stack");

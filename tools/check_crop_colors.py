from PIL import Image
import numpy as np

im = Image.open('shots/crop_current.png').convert('RGB')
arr = np.array(im)
# Find max intensity
gray = arr.max(axis=2)
print("Gray min:", gray.min(), "max:", gray.max(), "mean:", gray.mean())

# Print histogram of top intensities
hist, bins = np.histogram(gray, bins=10, range=(0, 256))
print("Histogram:", list(zip(bins[:-1], hist)))

# Find what the top 10 most common non-black colors are
from collections import Counter
colors = [tuple(p) for p in arr.reshape(-1, 3) if sum(p) > 50]
print("Top 10 colors:", Counter(colors).most_common(10))

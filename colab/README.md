# Colab Haptic Research Notebook

This folder contains a Colab-ready starter notebook for generating `output_haptic_map.json` from a sample video.

## Files
- `haptic_research_colab.ipynb`: uploads one video, runs audio + video analysis, and exports the JSON timeline.
- `requirements.txt`: optional local dependency list if you want to mirror the notebook environment outside Colab.

## How to use in Colab
1. Open the notebook in Colab.
2. Run the install and import cells.
3. Upload your sample clip when prompted.
4. Inspect the event table and preview output.
5. Download the generated JSON manually.

## Output
The notebook writes a JSON file with the same shape used by the Android player:
- `window_size_ms`
- `track`
- `events`
- `frames`

## Notes
- The notebook starts with a rule-based baseline for audio and video.
- Future cells are structured so YAMNet or a vision model can be plugged in later.
- The current workflow is research-first and Colab-only.
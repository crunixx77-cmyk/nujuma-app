#!/bin/bash

echo "Generating feature graphics to ~/nujuma-icons/nujuma-feature-graphic.png..."
mkdir -p ~/nujuma-icons/
rsvg-convert feature-graphic.svg > ~/nujuma-icons/feature-graphic.png

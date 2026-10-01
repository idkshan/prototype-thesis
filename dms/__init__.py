"""AI-based adaptive driver-monitoring system - detection model (version 1).

Pipeline:
    Stage 1  AI perception               dms.perception
    Stage 2  visual feature extraction   dms.features
    Stage 3  threshold classification    dms.classification
    Stage 4  severity grading            dms.severity
    Stage 5  driver-risk fusion + alerts dms.risk, dms.alerts
"""

__version__ = "1.0.0"

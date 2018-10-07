package com.github.alexmojaki.birdseye.pycharm;

import com.jetbrains.python.packaging.PyCondaPackageManagerImpl;
import com.jetbrains.python.packaging.PyPackageManager;

/**
 * birdseye must be installed using pip, not conda, and conda can't detect packages
 * installed by pip. This class disables conda package management (if it's being used)
 * and restores the original state after we're done with the package manager.
 */
public class CondaDisabler {

    PyCondaPackageManagerImpl condaPackageManager = null;

    /** Whether conda was being used before disabling */
    Boolean useConda = null;

    CondaDisabler(PyPackageManager packageManager) {
        if (packageManager instanceof PyCondaPackageManagerImpl) {
            condaPackageManager = (PyCondaPackageManagerImpl) packageManager;
        }
    }

    void disable() {
        if (condaPackageManager != null) {
            // lean towards useConda = true to avoid incorrectly disabling it permanently
            if (useConda == null || !useConda) {
                useConda = condaPackageManager.useConda();
            }
            condaPackageManager.useConda(false);
        }
    }

    void reset() {
        // only consider reenabling to avoid incorrectly disabling it permanently
        if (condaPackageManager != null && useConda) {
            condaPackageManager.useConda(true);
        }
    }
}

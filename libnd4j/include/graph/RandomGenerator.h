//
//  @author raver119@protonmail.com
//

#ifndef LIBND4J_GRAPH_RNG_H
#define LIBND4J_GRAPH_RNG_H

#include <types/u64.h>
#include <pointercast.h>
#include <op_boilerplate.h>

namespace nd4j {
    namespace graph {
        class RandomGenerator {
        private:
            // GRAPH-LEVEL STATE
            u64 _rootState;

            // NODE-LEVEL STATE
            u64 _nodeState;

            /**
             * Utility method, returns number of milliseconds since 1970
             */
            Nd4jLong currentMilliseconds();


            uint32_t xoroshiro(Nd4jLong index);

            /**
             * This method returns integer value between 0 and MAX_UINT
             */
            uint32_t relativeInt(Nd4jLong index);

        public:
            RandomGenerator(Nd4jLong rootSeed = 0, Nd4jLong nodeSeed = 0);
            ~RandomGenerator();

            /**
             * This method allows to change graph-level state in runtime.
             * PLEASE NOTE: this method will change state of node as well.
             */
            void setStates(Nd4jLong rootSeed, Nd4jLong nodeState = 0);

            

            /**
             * This method returns T value between from and to
             */
            template <typename T>
            T relativeT(Nd4jLong index, T from, T to);

            /**
             * This method returns T value between 0 and MAX_T
             */
            template <typename T>
            T relativeT(Nd4jLong index);


            void rewindH();
        };
    }
}

#endif
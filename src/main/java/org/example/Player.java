package org.example;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class MCTS {

    Node tree;

    private static final double EXPLORATION_CONSTANT = 1.414;

    long startTime;

    void runSimulations(GameState initialGameState, long durationInMs) {

        startTime = System.currentTimeMillis();

        if (tree != null) {
            // If the tree already exists, we attempt to find if an existing simulation ran for an actual gameState
            // As the case may be, it means that we probably already have some simulations for future events
            // In that case, we resume the tree to this specific state
            // Otherwise, we have to start from scratch in that case we set the tree to null and it will be initialized after
            //TODO to refine because we need to redefine what is the finite state of the game to make sure we can compare it
            tree = tree.findChildMatchingState(initialGameState).orElse(null);
            System.err.println("Node matching state : " + tree);
        }

        if (tree == null) {
            tree = new Node(initialGameState, null, null);
            System.err.println("Creating new tree");
        }
        
        while (System.currentTimeMillis() - startTime < durationInMs) {

            Node promisingNode = selectPromisingNode(tree);
            if (!promisingNode.getState().isTerminal() && promisingNode.children.isEmpty()) {
                expandNode(promisingNode);
            }
            Node nodeToSimulate = promisingNode;
            if (!promisingNode.getChildren().isEmpty()) {
                nodeToSimulate = promisingNode.getRandomChild();
            }
            double simulationResult = simulateRandomPlayout(nodeToSimulate);
            backPropagate(nodeToSimulate, simulationResult);
        }

        System.err.print("Current state has : " + tree.children.size() + " actions (nodes) possible");
        tree.children.forEach(child -> {
            System.err.println("child : " + child.getAction().getDescription() + ", has score of : " + child.totalScore + ", and number of visits : " + child.visitCount);
        });

        System.err.println("Ran simulations during " + (System.currentTimeMillis() - startTime));
    }

    public Node getBestChild() {
        return tree.getBestChild();
    }

    // select
    private Node selectPromisingNode(Node parent) {
        Node node = parent;
        while (!node.getChildren().isEmpty()) {
            node = node.getBestChildByUCB(EXPLORATION_CONSTANT);
        }
        return node;
    }

    // expand
    private void expandNode(Node node) {
        List<Action> possibleActions = node.getState().getPossibleActions();
        for (Action action : possibleActions) {
            GameState newState = node.getState().deepClone().applyAction(action);
            Node childNode = new Node(newState, action, node);
            node.addChild(childNode);
        }
    }

    // simulation (plays a random move from given node state)
    private double simulateRandomPlayout(Node node) {

        int maxDepth = 20;
        int depth = 0;

        GameState tempState = node.gameState.deepClone();
        while (!tempState.isTerminal() && depth < maxDepth) {
            List<Action> possibleActions = tempState.getPossibleActions();
            Action randomAction = possibleActions.get(new Random().nextInt(possibleActions.size()));
            tempState = tempState.applyAction(randomAction);
            depth++;
        }
        int score = tempState.getScoreForPlayer(Code4LifeGameState.ME);
        return score;
    }

    // back propagate
    private void backPropagate(Node node, double result) {
        Node currentNode = node;
        while (currentNode != null) {
            currentNode.incrementVisitCount();
            currentNode.addScore(result);
            currentNode = currentNode.getParent();
        }
    }

}

class Node {

    final GameState gameState;
    private final Action action;
    final Node parent;
    final List<Node> children;
    int visitCount;
    double totalScore;

    Node(GameState gameState, Action action, Node parent) {
        this.gameState = gameState;
        this.action = action;
        this.parent = parent;
        this.children = new ArrayList<>();
        this.visitCount = 0;
        this.totalScore = 0;
    }

    Node getBestChildByUCB(double explorationConstant) {
        return children.stream()
                .max(Comparator.comparingDouble(child -> (child.totalScore / child.visitCount) + explorationConstant * Math.sqrt(Math.log(this.visitCount) / child.visitCount)))
                .orElseThrow();
    }
    Node getBestChild() {
        return children.stream()
                .max(Comparator.comparingDouble(child -> child.totalScore / child.visitCount))
                .orElseThrow();
    }

    Node getRandomChild() { return children.get(new Random().nextInt(children.size())); }
    void addChild(Node child) { children.add(child); }
    public void incrementVisitCount() { visitCount++; }
    public void addScore(double score) { totalScore += score; }
    public List<Node> getChildren() { return children; }
    public GameState getState() { return gameState; }
    public Node getParent() { return parent; }
    public Action getAction() { return action; }

    public Optional<Node> findChildMatchingState(GameState state) {
        for (Node child : children) {
            if (child.gameState.equals(state)) {
                return Optional.of(child); // Si un enfant correspond au GameState donné, on le retourne
            }
        }
        return Optional.empty(); // Aucun enfant correspondant
    }
}

interface GameState {
    GameState applyAction(Action action);
    List<Action> getPossibleActions();
    GameState deepClone();
    boolean isTerminal();
    int getScoreForPlayer(int robotId);
}

class Code4LifeGameState implements GameState {

    private static final int MAX_TURNS = 200;
    public static final String SAMPLES = "SAMPLES";
    public static final String DIAGNOSIS = "DIAGNOSIS";
    public static final String MOLECULES = "MOLECULES";
    public static final String LABORATORY = "LABORATORY";
    public static final int ME = 0, HIM = 1;
    private final int currentTurn;

    final Robot[] robots;

    final List<Sample> samples;

    Code4LifeGameState(Robot[] robots, List<Sample> samples, int currentTurn) {
        this.robots = robots;
        this.samples = Collections.unmodifiableList(samples);
        this.currentTurn = currentTurn + 1;
    }

    @Override
    public GameState applyAction(Action action) {
        action.execute(this);
        return this;
    }

    //TODO only do ME actions, will account for other player later
    @Override
    public List<Action> getPossibleActions() {
        // Return the list of all available actions based on the current
        // For example I'm currently at the DIAGNOSIS module, I can Collect a sample
        // But I cannot produce med nor collect molecules for which i gotta go there first
        List<Action> possibleActions = new ArrayList<>();

        // All goto actions
        Stream.of(SAMPLES, DIAGNOSIS, MOLECULES, LABORATORY)
                .map(target -> new Goto(ME, target))
                .filter(action -> action.isCompatibleWith(this))
                .forEach(possibleActions::add);

        // All collect sample actions
        Stream.of(1, 2, 3)
                .map(rank -> new CollectSample(ME, rank))
                .filter(action -> action.isCompatibleWith(this))
                .forEach(possibleActions::add);

        // All diagnose sample actions
        robots[ME].samples.stream()
                .filter(Predicate.not(Sample::diagnosed))
                .map(unDiagnosedSample -> new DiagnoseSample(ME, unDiagnosedSample))
                .forEach(possibleActions::add);

        //TODO download and upload from the cloud later since i dont plan to do this currently until molecules are limited

        // All collect molecule actions
        Arrays.stream(Molecule.values())
                .map(molecule -> new CollectMolecule(ME, molecule))
                .filter(action -> action.isCompatibleWith(this))
                .forEach(possibleActions::add);

        // All produce med actions
        robots[ME].samples.stream()
                .map(sample -> new ProduceMed(ME, sample))
                .filter(action -> action.isCompatibleWith(this))
                .forEach(possibleActions::add);

        return possibleActions;
    }

    @Override
    public GameState deepClone() {
        return new Code4LifeGameState(Arrays.stream(robots).map(Robot::deepClone).toArray(Robot[]::new), new ArrayList<>(samples), currentTurn);
    }

    @Override
    public boolean isTerminal() {
        return currentTurn > MAX_TURNS;
    }

    //TODO the score shall account also for HIM and not just for ME
    @Override
    public int getScoreForPlayer(int playerId) {
        int score = 0;
        //TODO naive approach, refine

        // 100 points per diagnosed sample (incentize diagnosing so that further simulations will be able to reach score)
        score += robots[playerId].diagnosedSamples * 100;

        // 10 points per sample
        score += robots[playerId].samples.size() * 10;

        // 10 points per molecule
        score += robots[playerId].molecules.values().stream().mapToInt(k -> k).sum() * 10;

        // 1000 points per score
        score += robots[playerId].score * 1000;

        return score;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Code4LifeGameState that = (Code4LifeGameState) o;
        return Arrays.equals(robots, that.robots) && Objects.equals(samples, that.samples);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(samples);
        result = 31 * result + Arrays.hashCode(robots);
        return result;
    }
}

interface Action {
    boolean isCompatibleWith(GameState gameState);
    // Apply given action to the provided gameState
    void execute(GameState gameState);
    // Return a description of the action
    String getDescription();
}

class Goto implements Action {

    private final int playerId;
    private final String target;

    Goto(int playerId, String target) {
        this.playerId = playerId;
        this.target = target;
    }

    @Override
    public boolean isCompatibleWith(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
        return !state.robots[playerId].target.equals(target);
    }

    @Override
    public void execute(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
        state.robots[playerId].target = target;
    }

    @Override
    public String getDescription() {
        return "GOTO " + target;
    }
}

class CollectSample implements Action {

    private final int playerId;
    private final int rank;

    CollectSample(int playerId, int rank) {
        this.playerId = playerId;
        this.rank = rank;
    }

    @Override
    public boolean isCompatibleWith(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
        return state.robots[playerId].target.equals(Code4LifeGameState.SAMPLES) && state.robots[playerId].samples.size() < 3;
    }

    @Override
    public void execute(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
    }

    @Override
    public String getDescription() {
        return "CONNECT " + rank;
    }
}

class DiagnoseSample implements Action {

    private final int playerId;
    private final Sample sample;

    DiagnoseSample(int playerId, Sample sample) {
        this.playerId = playerId;
        this.sample = sample;
    }

    @Override
    public boolean isCompatibleWith(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
        return state.robots[playerId].target.equals(Code4LifeGameState.DIAGNOSIS) && !sample.diagnosed();
    }

    @Override
    public void execute(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
        state.robots[playerId].diagnosedSamples++;
    }

    @Override
    public String getDescription() {
        return "CONNECT " + sample.sampleId;
    }
}

class UploadSampleToCloud implements Action {
    private final int playerId;
    private final Sample sample;

    UploadSampleToCloud(int playerId, Sample sample) {
        this.playerId = playerId;
        this.sample = sample;
    }

    @Override
    public boolean isCompatibleWith(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
        return state.robots[playerId].target.equals(Code4LifeGameState.DIAGNOSIS) && sample.diagnosed();
    }

    @Override
    public void execute(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
        state.robots[playerId].sampleCount--;
    }

    @Override
    public String getDescription() {
        return "CONNECT " + sample.sampleId;
    }
}

class DownloadSampleFromCloud implements Action {
    private final int playerId;
    private final Sample sample;

    DownloadSampleFromCloud(int playerId, Sample sample) {
        this.playerId = playerId;
        this.sample = sample;
    }

    @Override
    public boolean isCompatibleWith(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
        return state.robots[playerId].target.equals(Code4LifeGameState.DIAGNOSIS) && sample.diagnosed();
    }

    @Override
    public void execute(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
        state.robots[playerId].sampleCount++;
    }

    @Override
    public String getDescription() {
        return "CONNECT " + sample.sampleId;
    }
}

class CollectMolecule implements Action {
    private final int playerId;
    private final Molecule molecule;
    CollectMolecule(int playerId, Molecule molecule) {
        this.playerId = playerId;
        this.molecule = molecule;
    }

    @Override
    public boolean isCompatibleWith(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
        return state.robots[playerId].target.equals(Code4LifeGameState.MOLECULES) && state.robots[playerId].moleculesCount() < 10;
    }

    @Override
    public void execute(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
        state.robots[playerId].molecules.compute(molecule, (k, v) -> v + 1);
    }

    @Override
    public String getDescription() {
        return "CONNECT " + molecule.name();
    }
}

class ProduceMed implements Action {

    private final int playerId;
    private final Sample sample;

    ProduceMed(int playerId, Sample sample) {
        this.playerId = playerId;
        this.sample = sample;
    }

    @Override
    public boolean isCompatibleWith(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
        return state.robots[playerId].target.equals(Code4LifeGameState.LABORATORY) && state.robots[playerId].hasSufficientMolecules(sample.molecules);
    }

    @Override
    public void execute(GameState gameState) {
        Code4LifeGameState state = (Code4LifeGameState) gameState;
        Map<Molecule, Integer> playersMolecules = state.robots[playerId].molecules;
        // Remove the required number of molecules for given sample and given player from its stock of molecules
        sample.molecules.forEach((molecule, requiredNumberOfGivenMolecule) -> {
            Integer numberOfGivenMoleculesForPlayer = playersMolecules.get(molecule);
            if (numberOfGivenMoleculesForPlayer >= requiredNumberOfGivenMolecule) {
                playersMolecules.replace(molecule, numberOfGivenMoleculesForPlayer - requiredNumberOfGivenMolecule);
            } else {
                throw new RuntimeException("Player :" + playerId + ", has not enough molecules: " + molecule + ", for sample : " + sample.sampleId);
            }
        });
        state.robots[playerId].samples.remove(sample);
        state.robots[playerId].removeMolecules(sample.molecules);
        state.robots[playerId].score += sample.health;
    }

    @Override
    public String getDescription() {
        return "CONNECT " + sample.sampleId;
    }
}

class Robot {
    public int diagnosedSamples;
    int sampleCount;
    String target;
    int score;
    Map<Molecule, Integer> molecules;
    List<Sample> samples;

    Robot(String target, int score, List<Sample> samples, int... storage) {
        this.target = target;
        this.score = score;
        this.samples = samples;
        this.sampleCount = samples.size();
        this.diagnosedSamples = (int) samples.stream()
                .filter(Sample::diagnosed)
                .count();
        molecules = new EnumMap<>(Molecule.class);
        molecules.put(Molecule.A, storage[0]);
        molecules.put(Molecule.B, storage[1]);
        molecules.put(Molecule.C, storage[2]);
        molecules.put(Molecule.D, storage[3]);
        molecules.put(Molecule.E, storage[4]);
    }

    int moleculesCount() {
        return molecules.values().stream().mapToInt(k -> k).sum();
    }

    public boolean hasSufficientMolecules(Map<Molecule, Integer> requiredMolecules) {
        for (Map.Entry<Molecule, Integer> entry : requiredMolecules.entrySet()) {
            Molecule molecule = entry.getKey();
            int requiredAmount = entry.getValue();

            int availableAmount = molecules.getOrDefault(molecule, 0);
            if (availableAmount < requiredAmount) {
                return false;
            }
        }
        return true;
    }

    public void removeMolecules(Map<Molecule, Integer> moleculesToRemove) {
        moleculesToRemove.forEach((k, v) -> {
            molecules.compute(k, (x, y) -> {
                int remainingMolecules = v - y;
                return Math.max(remainingMolecules, 0);
            });
        });
    }

    Robot deepClone() {
        Robot robot = new Robot(target, score, new ArrayList<>(samples),
                molecules.get(Molecule.A),
                molecules.get(Molecule.B),
                molecules.get(Molecule.C),
                molecules.get(Molecule.D),
                molecules.get(Molecule.E));
        return robot;
    }
}

class Sample {
    final int sampleId;
    final int carriedBy;
    final int rank;
    final String expertiseGain;
    final int health;
    final Map<Molecule, Integer> molecules;
    Sample(int sampleId, int carriedBy, int rank, String expertiseGain, int health, int... molecules) {
        this.sampleId = sampleId;
        this.carriedBy = carriedBy;
        this.rank = rank;
        this.expertiseGain = expertiseGain;
        this.health = health;
        this.molecules = new EnumMap<>(Molecule.class);
        this.molecules.put(Molecule.A, molecules[0]);
        this.molecules.put(Molecule.B, molecules[1]);
        this.molecules.put(Molecule.C, molecules[2]);
        this.molecules.put(Molecule.D, molecules[3]);
        this.molecules.put(Molecule.E, molecules[4]);
    }

    public boolean diagnosed() {
        return !molecules.isEmpty();
    }

    @Override
    public String toString() {
        return "Sample{" +
                "sampleId=" + sampleId +
                ", carriedBy=" + carriedBy +
                ", rank=" + rank +
                ", expertiseGain='" + expertiseGain + '\'' +
                ", health=" + health +
                ", molecules=" + molecules +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Sample sample = (Sample) o;
        return sampleId == sample.sampleId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sampleId);
    }

}

enum Molecule {
    A, B, C, D, E
}

class Player {

    private static MCTS mcts;

    public static void main(String args[]) {
        Scanner in = new Scanner(System.in);
        int projectCount = in.nextInt();
        for (int i = 0; i < projectCount; i++) {
            int a = in.nextInt();
            int b = in.nextInt();
            int c = in.nextInt();
            int d = in.nextInt();
            int e = in.nextInt();
        }

        int turn = 0;

        // game loop
        while (true) {

            Robot[] robots = new Robot[2];

            for (int i = 0; i < 2; i++) {
                String target = in.next();
                int eta = in.nextInt();
                int score = in.nextInt();
                int storageA = in.nextInt();
                int storageB = in.nextInt();
                int storageC = in.nextInt();
                int storageD = in.nextInt();
                int storageE = in.nextInt();
                int expertiseA = in.nextInt();
                int expertiseB = in.nextInt();
                int expertiseC = in.nextInt();
                int expertiseD = in.nextInt();
                int expertiseE = in.nextInt();
                robots[i] = new Robot(target, score, new ArrayList<>(), storageA, storageB, storageC, storageD, storageE);
            }
            int availableA = in.nextInt();
            int availableB = in.nextInt();
            int availableC = in.nextInt();
            int availableD = in.nextInt();
            int availableE = in.nextInt();
            int sampleCount = in.nextInt();

            List<Sample> samples = new ArrayList<>();
            for (int i = 0; i < sampleCount; i++) {
                int sampleId = in.nextInt();
                int carriedBy = in.nextInt();
                int rank = in.nextInt();
                String expertiseGain = in.next();
                int health = in.nextInt();
                int costA = in.nextInt();
                int costB = in.nextInt();
                int costC = in.nextInt();
                int costD = in.nextInt();
                int costE = in.nextInt();
                Sample sample = new Sample(sampleId, carriedBy, rank, expertiseGain, health, costA, costB, costC, costD, costE);
                samples.add(sample);
                System.err.println(sample);
            }

            for (int i = 0; i < 2; i++) {
                final int playerId = i;
                robots[i].samples.addAll(samples.stream().filter(sample -> sample.carriedBy == playerId).collect(Collectors.toList()));
                robots[i].sampleCount = robots[i].samples.size();
                robots[i].diagnosedSamples = (int) robots[i].samples.stream().filter(Sample::diagnosed).count();
            }

            GameState state = new Code4LifeGameState(robots, samples, turn);

            Action action;
            if (turn == 0) {
                action = computeFirstMove(state);
            } else {
                action = computeNextMove(state);
            }

            turn++;

        }
    }

    public static Action computeFirstMove(GameState initialState) {
        mcts = new MCTS();
        mcts.runSimulations(initialState, 30L);
        return mcts.getBestChild().getAction();
    }

    public static Action computeNextMove(GameState currentState) {
        mcts.runSimulations(currentState, 30L);
        return mcts.getBestChild().getAction();
    }

}
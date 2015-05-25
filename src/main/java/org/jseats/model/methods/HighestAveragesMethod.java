package org.jseats.model.methods;

import static org.jseats.Properties.NUMBER_OF_SEATS;

import java.util.List;
import java.util.Properties;

import org.jseats.model.Candidate;
import org.jseats.model.InmutableTally;
import org.jseats.model.Result;
import org.jseats.model.Result.ResultType;
import org.jseats.model.SeatAllocationException;
import org.jseats.model.SeatAllocationMethod;
import org.jseats.model.tie.TieBreaker;
import org.jseats.model.tie.TieScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HighestAveragesMethod implements SeatAllocationMethod {

	static Logger log = LoggerFactory.getLogger(HighestAveragesMethod.class);

	public abstract double nextDivisor(int round);

	@Override
	public Result process(InmutableTally tally, Properties properties, TieBreaker tieBreaker) throws SeatAllocationException {

		if (null == tally) {
			throw new SeatAllocationException("Received Tally was null");
		}
		if (null == properties) {
			throw new SeatAllocationException("Received Properties was null");
		}
		int numberOfCandidates = tally.getNumberOfCandidates();
		if (numberOfCandidates <= 0) {
			throw new SeatAllocationException("This tally contains no candidates");
		}

		// If numberOfSeats is not defined it is set with a default value
		// to numberOfCandidates
		int numberOfSeats = 0;
		String numberOfSeatsString_or_NumberOfCandidates =
			properties.getProperty(NUMBER_OF_SEATS, Integer.toString(numberOfCandidates));
		try {
			numberOfSeats = Integer.parseInt(numberOfSeatsString_or_NumberOfCandidates);
		} catch (NumberFormatException exception) {
			throw new SeatAllocationException("numberOfSeats property is not a number: '" +
				properties.getProperty(NUMBER_OF_SEATS) + "'");
		}
		if (numberOfSeats < 0) {
			throw new SeatAllocationException("numberOfSeats is negative: " + numberOfSeats);
		}
		// int numberOfSeats = Integer.parseInt(properties.getProperty(
		// "numberOfSeats", Integer.toString(numberOfCandidates)));
		double firstDivisor = Double.parseDouble(properties.getProperty("firstDivisor", "-1"));
		boolean modifiedFirstDivisor = (firstDivisor == -1) ? false : true;
		boolean groupSeatsPerCandidate = Boolean.parseBoolean(properties.getProperty("groupSeatsPerCandidate", "false"));

		int numberOfUnallocatedSeats = numberOfSeats;

		int[] seatsPerCandidate = new int[numberOfCandidates];
		double[][] averagesPerRound = new double[numberOfCandidates][numberOfSeats];

		log.debug("numberOfSeats: " + numberOfSeats);
		log.debug("groupSeatsPerCandidate: " + groupSeatsPerCandidate);

		// Create the averages table
		for (int round = 0; round < numberOfSeats; round++) {

			double divisor;
			if (modifiedFirstDivisor) {
				// Then user has provided an alternative first divisor
				divisor = firstDivisor;
				modifiedFirstDivisor = false;
				nextDivisor(round); // Ignore first methods' first divisor
			} else
				divisor = nextDivisor(round);

			// Let's divide every candidate's votes with the current round's
			// divisor.

			StringBuilder averagesForThisRound = new StringBuilder();
			averagesForThisRound.append(round + " / " + divisor + " : ");

			for (int candidate = 0; candidate < numberOfCandidates; candidate++) {
				averagesPerRound[candidate][round] = (tally.getCandidateAt(candidate).getVotes() / divisor);

				averagesForThisRound.append(String.format("%.2f", averagesPerRound[candidate][round]) + ",\t");
			}

			// log.debug("Current divisor: " + divisor);

			log.debug(averagesForThisRound.toString());
		}

		Result result = new Result(ResultType.MULTIPLE);

		// Find max votes of the average table and add a seat to the appropriate
		// candidate.
		while (numberOfUnallocatedSeats > 0) {

			int maxCandidate = -1;
			int maxRound = -1;
			double maxVotes = -1;

			for (int round = 0; round < numberOfSeats; round++) {
				for (int candidate = 0; candidate < numberOfCandidates; candidate++) {

					if (averagesPerRound[candidate][round] == maxVotes) {

						log.debug("Tie between  " + tally.getCandidateAt(maxCandidate) + " and " +
							tally.getCandidateAt(candidate));

						if (tieBreaker != null) {

							log.debug("Using tie breaker: " + tieBreaker.getName());

							// Inputs Swapped, to natural matrix traversing order so it's coherent with maxVotes
							TieScenario topCandidate =
								tieBreaker.breakTie(tally.getCandidateAt(maxCandidate), tally.getCandidateAt(candidate));

							if (topCandidate == null || topCandidate.isTied()) {
								Result tieResult = new Result(ResultType.TIE);
								tieResult.addSeat(tally.getCandidateAt(maxCandidate));
								tieResult.addSeat(tally.getCandidateAt(candidate));

								return tieResult;
							} else {
								maxCandidate = tally.getCandidateIndex(topCandidate.get(0));
								// Bug #1 : that breaks logic? -> maxVotes = averagesPerRound[maxCandidate][round];
								// Bug #2: maxRound setting is missing (important when clearing cell)
								maxRound = (maxCandidate == candidate) ? round : maxRound;
							}

						} else {
							Result tieResult = new Result(ResultType.TIE);
							tieResult.addSeat(tally.getCandidateAt(maxCandidate));
							tieResult.addSeat(tally.getCandidateAt(candidate));

							return tieResult;
						}

					} else if (averagesPerRound[candidate][round] > maxVotes) {
						maxCandidate = candidate;
						maxRound = round;
						maxVotes = averagesPerRound[candidate][round];
					}
				}
			}

			seatsPerCandidate[maxCandidate]++;

			if (!groupSeatsPerCandidate)
				result.addSeat(tally.getCandidateAt(maxCandidate));

			log.debug("Found maximum " + maxVotes + " at: " + tally.getCandidateAt(maxCandidate).getName() + " : " +
				maxRound);

			// Eliminate this maximum coordinates and iterate
			averagesPerRound[maxCandidate][maxRound] = -2;
			numberOfUnallocatedSeats--;
		}

		for (int candidate = 0; candidate < numberOfCandidates; candidate++) {
			log.trace(tally.getCandidateAt(candidate) + " has ended with " + seatsPerCandidate[candidate] + " seats.");
		}

		if (groupSeatsPerCandidate) {
			// Time to spread allocated seats to results

			log.trace("Grouping candidates");

			for (int candidate = 0; candidate < numberOfCandidates; candidate++) {
				for (int seat = 0; seat < seatsPerCandidate[candidate]; seat++) {
					result.addSeat(tally.getCandidateAt(candidate));
				}
			}
		}

		return result;
	}
}
